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
    fun detect(bitmap: Bitmap): List<BubbleBox> {
        val aspect = bitmap.height.toFloat() / bitmap.width
        val boxes = if (aspect > MAX_ASPECT_RATIO) detectStrip(bitmap) else detectPage(bitmap)
        return boxes
            .map { it.clampTo(bitmap.width, bitmap.height) }
            .filter { it.isUsable() }
    }

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

                    if (right - left > MIN_BOX_SIDE && bottom - top > MIN_BOX_SIDE) {
                        candidates += floatArrayOf(left, top, right, bottom, confidence)
                    }
                }
            }
        }

        return nonMaxSuppression(candidates).toBoxes()
    }

    private fun detectStrip(bitmap: Bitmap): List<BubbleBox> {
        val collected = mutableListOf<FloatArray>()
        var y = 0
        while (y < bitmap.height) {
            val chunkHeight = minOf(CHUNK_HEIGHT, bitmap.height - y)
            if (chunkHeight < MIN_CHUNK_HEIGHT) break

            val chunk = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, chunkHeight)
            val detections = detectPage(chunk)
            chunk.recycle()

            for (box in detections) {
                collected += floatArrayOf(
                    box.left.toFloat(),
                    (box.top + y).toFloat(),
                    box.right.toFloat(),
                    (box.bottom + y).toFloat(),
                    box.confidence,
                )
            }

            y += CHUNK_HEIGHT - CHUNK_OVERLAP
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
            remaining.removeAll {
                intersectionOverUnion(best, it) >= IOU_THRESHOLD || isMostlyInside(it, best)
            }
        }
        return kept
    }

    /**
     * True when [candidate] sits almost entirely within [keeper].
     *
     * Intersection-over-union alone does not suppress a nested detection: a small box inside a large
     * one shares little of their combined area, so both survive. Both then get filled and lettered
     * independently, and the bubble ends up with two translations stacked on top of each other at
     * different sizes. What matters for a duplicate is how much of the *smaller* box is covered.
     */
    private fun isMostlyInside(candidate: FloatArray, keeper: FloatArray): Boolean {
        val left = maxOf(candidate[0], keeper[0])
        val top = maxOf(candidate[1], keeper[1])
        val right = minOf(candidate[2], keeper[2])
        val bottom = minOf(candidate[3], keeper[3])
        if (right <= left || bottom <= top) return false
        val intersection = (right - left) * (bottom - top)
        val candidateArea = (candidate[2] - candidate[0]) * (candidate[3] - candidate[1])
        if (candidateArea <= 0f) return true
        return intersection / candidateArea >= CONTAINMENT_THRESHOLD
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
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.5f
        /** Share of a nested box that must be covered before it counts as a duplicate. */
        const val CONTAINMENT_THRESHOLD = 0.65f
        const val LETTERBOX_GRAY = 114
        const val MIN_BOX_SIDE = 10

        const val MAX_ASPECT_RATIO = 3.0f
        const val CHUNK_HEIGHT = 1500
        const val CHUNK_OVERLAP = 200
        const val MIN_CHUNK_HEIGHT = 50
    }
}
