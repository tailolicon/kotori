package mihon.feature.translation.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import mihon.feature.translation.model.BubbleBox
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer

/**
 * YOLOv8 speech-bubble detector running through ONNX Runtime, fully on-device.
 *
 * The weights live in `assets/translation/bubble-detector.onnx` and are copied out to internal
 * storage on first use so ONNX Runtime can memory-map them instead of holding the whole graph on the
 * heap.
 *
 * Every method blocks; call from a background dispatcher.
 */
class BubbleDetector(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * The extracted model, created on first use and repaired if it turns out to be unusable.
     *
     * "Exists and is non-empty" was the old test for whether extraction was needed, and it is not
     * enough. A copy interrupted by a crash, a full disk, or a shipped asset that is itself damaged
     * leaves a file that passes that test and fails to parse — and because nothing ever replaced it,
     * translation stayed dead for every page, every provider, indefinitely. Extraction is now atomic
     * (write to a temporary file, then rename), the copy is size-checked against the asset, and a
     * session that fails to open gets exactly one retry from a freshly extracted copy.
     */
    private val session: OrtSession by lazy {
        val modelFile = File(context.filesDir, MODEL_FILE_NAME)
        if (!isExtractionCurrent(modelFile)) extractModel(modelFile)
        try {
            openSession(modelFile)
        } catch (first: Throwable) {
            logcat { "Bubble detector failed to load: ${first.message}; re-extracting and retrying" }
            extractModel(modelFile)
            openSession(modelFile)
        }
    }

    private fun openSession(modelFile: File): OrtSession =
        env.createSession(modelFile.absolutePath).also {
            logcat { "Bubble detector ready: inputs=${it.inputNames} outputs=${it.outputNames}" }
        }

    /** True when the extracted file is present and the same size as the asset it came from. */
    private fun isExtractionCurrent(modelFile: File): Boolean {
        if (!modelFile.isFile || modelFile.length() == 0L) return false
        val assetLength = runCatching {
            context.assets.openFd(MODEL_ASSET_PATH).use { it.length }
        }.getOrElse {
            // Compressed assets have no file descriptor; fall back to reading the stream's length.
            runCatching {
                context.assets.open(MODEL_ASSET_PATH).use { input ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    total
                }
            }.getOrNull()
        } ?: return true // Cannot measure the asset; assume what is on disk is fine.
        return modelFile.length() == assetLength
    }

    private fun extractModel(modelFile: File) {
        val temp = File(modelFile.parentFile, "${modelFile.name}.tmp")
        temp.delete()
        context.assets.open(MODEL_ASSET_PATH).use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        modelFile.delete()
        check(temp.renameTo(modelFile)) { "Could not install the bubble detector model" }
        logcat { "Extracted bubble detector model (${modelFile.length()} bytes)" }
    }

    /**
     * Detects speech bubbles. Webtoon-style strips are processed as overlapping chunks because a
     * single 640x640 letterbox of a 1:20 image leaves bubbles only a few pixels tall.
     */
    fun detect(bitmap: Bitmap, horizontalSeams: IntArray = intArrayOf()): List<BubbleBox> {
        val aspect = bitmap.height.toFloat() / bitmap.width
        val boxes = when {
            horizontalSeams.isNotEmpty() -> detectSeamedStrip(bitmap, horizontalSeams)
            aspect > MAX_ASPECT_RATIO -> detectStrip(bitmap)
            else -> detectPage(bitmap)
        }
        return boxes
            .map { it.clampTo(bitmap.width, bitmap.height) }
            .filter { it.isUsable() }
    }

    /**
     * Detects each original source page in its own frame, then adds only unsupported boxes that a
     * continuous pass found crossing a seam. Page-local manga geometry is therefore identical in
     * direct and prefetch translation, while a manhwa balloon genuinely split between source slices
     * can still be reconstructed by the joined pass.
     */
    private fun detectSeamedStrip(bitmap: Bitmap, horizontalSeams: IntArray): List<BubbleBox> {
        val seams = horizontalSeams.filter { it in 1 until bitmap.height }.distinct().sorted()
        if (seams.isEmpty()) return detectStrip(bitmap)

        val boundaries = listOf(0) + seams + bitmap.height
        val pageAligned = boundaries.zipWithNext().flatMap { (top, bottom) ->
            val page = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
            try {
                detectPage(page).map { box -> box.copy(top = box.top + top, bottom = box.bottom + top) }
            } finally {
                if (page !== bitmap) page.recycle()
            }
        }

        val continuous = detectStrip(bitmap)
        val supplementalIndices = SeamedDetectionGuard.supplementalIndices(
            continuous = continuous.map { it.toGuardBounds() },
            pageAligned = pageAligned.map { it.toGuardBounds() },
            seams = seams.toIntArray(),
        )
        if (supplementalIndices.isNotEmpty()) {
            logcat { "Kept ${supplementalIndices.size} unsupported bubble(s) from strip detection" }
        }
        return pageAligned + supplementalIndices.map(continuous::get)
    }

    private fun BubbleBox.toGuardBounds() =
        SeamedDetectionGuard.Bounds(left = left, top = top, right = right, bottom = bottom)

    private fun detectPage(bitmap: Bitmap): List<BubbleBox> {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height

        val scale = minOf(INPUT_SIZE.toFloat() / imageWidth, INPUT_SIZE.toFloat() / imageHeight)
        val scaledWidth = (imageWidth * scale).toInt()
        val scaledHeight = (imageHeight * scale).toInt()
        val padX = (INPUT_SIZE - scaledWidth) / 2f
        val padY = (INPUT_SIZE - scaledHeight) / 2f

        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.rgb(LETTERBOX_GRAY, LETTERBOX_GRAY, LETTERBOX_GRAY))
            drawBitmap(bitmap, null, RectF(padX, padY, padX + scaledWidth, padY + scaledHeight), null)
        }

        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(pixelCount)
        letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        letterboxed.recycle()

        // NCHW, RGB, normalised to [0,1] — the layout the exported graph expects.
        val buffer = FloatBuffer.allocate(3 * pixelCount)
        for (i in 0 until pixelCount) buffer.put((pixels[i] shr 16 and 0xFF) / 255f)
        for (i in 0 until pixelCount) buffer.put((pixels[i] shr 8 and 0xFF) / 255f)
        for (i in 0 until pixelCount) buffer.put((pixels[i] and 0xFF) / 255f)
        buffer.rewind()

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val candidates = mutableListOf<FloatArray>()

        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = (results[0] as OnnxTensor).value as Array<Array<FloatArray>>
                val predictions = output[0][4].size

                for (i in 0 until predictions) {
                    val confidence = output[0][4][i]
                    if (confidence < CONF_THRESHOLD) continue

                    val cx = output[0][0][i]
                    val cy = output[0][1][i]
                    val w = output[0][2][i]
                    val h = output[0][3][i]

                    val left = ((cx - w / 2 - padX) / scale).coerceIn(0f, imageWidth.toFloat())
                    val top = ((cy - h / 2 - padY) / scale).coerceIn(0f, imageHeight.toFloat())
                    val right = ((cx + w / 2 - padX) / scale).coerceIn(0f, imageWidth.toFloat())
                    val bottom = ((cy + h / 2 - padY) / scale).coerceIn(0f, imageHeight.toFloat())

                    val proposalWidth = right - left
                    val proposalHeight = bottom - top
                    if (
                        proposalWidth > MIN_BOX_SIDE &&
                        proposalHeight > MIN_BOX_SIDE &&
                        DetectorProposalPolicy.shouldKeep(confidence, proposalWidth, proposalHeight)
                    ) {
                        candidates += floatArrayOf(left, top, right, bottom, confidence)
                    }
                }
            }
        }

        val suppressed = nonMaxSuppression(candidates)
        val selected = DetectorProposalSelector.keepIndices(
            suppressed.map {
                DetectorProposalSelector.Proposal(it[0], it[1], it[2], it[3], it[4])
            },
            imageWidth,
            imageHeight,
        )
        return selected.map(suppressed::get).toBoxes()
    }

    private fun detectStrip(bitmap: Bitmap): List<BubbleBox> {
        val collected = mutableListOf<FloatArray>()
        for (y in StripWindowPlanner.starts(bitmap.height, CHUNK_HEIGHT, CHUNK_OVERLAP)) {
            val chunkHeight = minOf(CHUNK_HEIGHT, bitmap.height - y)
            if (chunkHeight < MIN_CHUNK_HEIGHT) break

            // A balloon may legitimately be clipped by the top/bottom of a webtoon source slice.
            // Giving only the outer strip edges some neutral context prevents the detector from
            // treating that lettering as image-edge noise. Interior windows already get equivalent
            // context from the deliberately large overlap below.
            val padTop = if (y == 0) STRIP_EDGE_CONTEXT else 0
            val padBottom = if (y + chunkHeight == bitmap.height) STRIP_EDGE_CONTEXT else 0
            val chunk = if (padTop + padBottom == 0) {
                Bitmap.createBitmap(bitmap, 0, y, bitmap.width, chunkHeight)
            } else {
                Bitmap.createBitmap(
                    bitmap.width,
                    chunkHeight + padTop + padBottom,
                    Bitmap.Config.ARGB_8888,
                ).also { padded ->
                    Canvas(padded).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, (padTop - y).toFloat(), null)
                    }
                }
            }
            val detections = detectPage(chunk)
            chunk.recycle()

            for (box in detections) {
                collected += floatArrayOf(
                    box.left.toFloat(),
                    (box.top + y - padTop).coerceIn(0, bitmap.height).toFloat(),
                    box.right.toFloat(),
                    (box.bottom + y - padTop).coerceIn(0, bitmap.height).toFloat(),
                    box.confidence,
                )
            }
        }
        return nonMaxSuppression(collected).toBoxes()
    }

    private fun List<FloatArray>.toBoxes(): List<BubbleBox> = map {
        BubbleBox(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4])
    }

    private fun nonMaxSuppression(boxes: List<FloatArray>): List<FloatArray> {
        val remaining = boxes.sortedByDescending { it[4] }.toMutableList()
        val kept = mutableListOf<FloatArray>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            kept += best
            // A small word can legitimately sit inside a coarse panel-sized proposal. Suppressing
            // by containment used to delete WAIT/YOU before OCR could prove they were separate
            // dialogue. IoU removes near-identical proposals here; content-aware nested dedupe runs
            // after OCR in SpeechDuplicateResolver.
            remaining.removeAll { intersectionOverUnion(best, it) >= IOU_THRESHOLD }
        }
        return kept
    }

    private fun intersectionOverUnion(a: FloatArray, b: FloatArray): Float {
        val left = maxOf(a[0], b[0])
        val top = maxOf(a[1], b[1])
        val right = minOf(a[2], b[2])
        val bottom = minOf(a[3], b[3])
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return intersection / (areaA + areaB - intersection)
    }

    fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val MODEL_ASSET_PATH = "translation/bubble-detector.onnx"
        const val MODEL_FILE_NAME = "translation-bubble-detector.onnx"

        const val INPUT_SIZE = 640
        // Tiny manga words can score below 0.10 (YOU varies around 0.055-0.074 by bitmap resampler).
        // DetectorProposalPolicy admits only
        // compact line-shaped candidates in that range, and LowConfidenceSpeechGuard still requires
        // credible OCR before any weak box can affect artwork.
        const val CONF_THRESHOLD = 0.055f
        const val IOU_THRESHOLD = 0.5f
        const val LETTERBOX_GRAY = 114
        const val MIN_BOX_SIDE = 10

        const val MAX_ASPECT_RATIO = 3.0f
        // Keep lettering large enough at the detector's 640 px input. A 1,800 px window shrank the
        // ordinary 25-35 px manhwa font below the model's useful resolution.
        const val CHUNK_HEIGHT = 1200
        // Must cover the tallest ordinary manhwa balloon so every interior balloon is complete in
        // at least one detector window. The former 200 px overlap split 500-800 px balloons and
        // silently left their English text untouched.
        const val CHUNK_OVERLAP = 800
        const val STRIP_EDGE_CONTEXT = 160
        const val MIN_CHUNK_HEIGHT = 50
    }
}
