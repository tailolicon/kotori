package mihon.feature.translation.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer

/**
 * YOLOv8 speech-bubble detector using Manga-Translator `model/model.pt` (exported ONNX).
 *
 * Constants and long-image slicing match `detect_bubbles.py`.
 */
internal class MangaYoloDetector(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        val file = MangaOnnxStore.extract(context, MODEL_ASSET, MODEL_FILE)
        try {
            env.createSession(file.absolutePath)
        } catch (first: Throwable) {
            logcat { "Manga YOLO failed to load: ${first.message}; re-extracting" }
            file.delete()
            val retry = MangaOnnxStore.extract(context, MODEL_ASSET, MODEL_FILE)
            env.createSession(retry.absolutePath)
        }.also {
            logcat { "Manga YOLO ready: inputs=${it.inputNames} outputs=${it.outputNames}" }
        }
    }

    fun detect(bitmap: Bitmap): List<MangaBlobs.Box> {
        val aspect = bitmap.height.toFloat() / bitmap.width
        val yolo = if (aspect > MAX_ASPECT_RATIO) detectLong(bitmap) else detectPage(bitmap)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val black = MangaBlobs.detectBlackBubbles(pixels, bitmap.width, bitmap.height)
        val merged = if (black.isEmpty()) yolo else MangaBlobs.nms(yolo + black)
        return merged.map { box ->
            box.copy(
                left = box.left.coerceIn(0, bitmap.width),
                top = box.top.coerceIn(0, bitmap.height),
                right = box.right.coerceIn(0, bitmap.width),
                bottom = box.bottom.coerceIn(0, bitmap.height),
            )
        }.filter { it.right - it.left > 8 && it.bottom - it.top > 8 }
    }

    private fun detectLong(bitmap: Bitmap): List<MangaBlobs.Box> {
        val cuts = safeCuts(bitmap)
        return if (cuts.isNotEmpty()) {
            val bounds = listOf(0) + cuts + bitmap.height
            val all = ArrayList<MangaBlobs.Box>()
            for (i in 0 until bounds.size - 1) {
                val y0 = bounds[i]
                val y1 = bounds[i + 1]
                if (y1 - y0 < 50) continue
                val chunk = Bitmap.createBitmap(bitmap, 0, y0, bitmap.width, y1 - y0)
                try {
                    all += detectPage(chunk).map { it.copy(top = it.top + y0, bottom = it.bottom + y0) }
                } finally {
                    chunk.recycle()
                }
            }
            MangaBlobs.nms(all)
        } else {
            detectOverlap(bitmap)
        }
    }

    private fun detectOverlap(bitmap: Bitmap): List<MangaBlobs.Box> {
        val all = ArrayList<MangaBlobs.Box>()
        var y = 0
        while (y < bitmap.height) {
            val yEnd = minOf(y + MAX_CHUNK_HEIGHT, bitmap.height)
            if (yEnd - y < 50) break
            val chunk = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, yEnd - y)
            try {
                all += detectPage(chunk).map { it.copy(top = it.top + y, bottom = it.bottom + y) }
            } finally {
                chunk.recycle()
            }
            if (yEnd >= bitmap.height) break
            y = yEnd - OVERLAP
        }
        return MangaBlobs.nms(all)
    }

    private fun safeCuts(bitmap: Bitmap): List<Int> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rowMean = DoubleArray(bitmap.height)
        for (y in 0 until bitmap.height) {
            var sum = 0L
            val row = y * bitmap.width
            for (x in 0 until bitmap.width) sum += MangaPixels.gray(pixels[row + x])
            rowMean[y] = sum.toDouble() / bitmap.width
        }
        val gutters = ArrayList<Triple<Int, Int, Int>>()
        var start: Int? = null
        for (i in rowMean.indices) {
            val gutter = rowMean[i] > WHITE_THRESHOLD || rowMean[i] < BLACK_ROW
            if (gutter && start == null) start = i
            else if (!gutter && start != null) {
                if (i - start >= GUTTER_MIN) gutters += Triple(start, i, (start + i) / 2)
                start = null
            }
        }
        if (start != null && bitmap.height - start >= GUTTER_MIN) {
            gutters += Triple(start, bitmap.height, (start + bitmap.height) / 2)
        }
        if (gutters.isEmpty()) return emptyList()
        val cuts = ArrayList<Int>()
        var last = 0
        for ((_, _, center) in gutters) {
            if (center - last >= MIN_CHUNK_HEIGHT && center - last >= MAX_CHUNK_HEIGHT * 0.7) {
                cuts += center
                last = center
            }
        }
        return cuts
    }

    private fun detectPage(bitmap: Bitmap): List<MangaBlobs.Box> {
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
        val buffer = FloatBuffer.allocate(3 * pixelCount)
        for (i in 0 until pixelCount) buffer.put((pixels[i] shr 16 and 0xFF) / 255f)
        for (i in 0 until pixelCount) buffer.put((pixels[i] shr 8 and 0xFF) / 255f)
        for (i in 0 until pixelCount) buffer.put((pixels[i] and 0xFF) / 255f)
        buffer.rewind()
        val candidates = mutableListOf<MangaBlobs.Box>()
        OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())).use { tensor ->
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
                    val left = ((cx - w / 2 - padX) / scale).toInt()
                    val top = ((cy - h / 2 - padY) / scale).toInt()
                    val right = ((cx + w / 2 - padX) / scale).toInt()
                    val bottom = ((cy + h / 2 - padY) / scale).toInt()
                    if (right - left > 8 && bottom - top > 8) {
                        candidates += MangaBlobs.Box(left, top, right, bottom, confidence, false)
                    }
                }
            }
        }
        return MangaBlobs.nms(candidates)
    }

    fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val MODEL_ASSET = "translation/manga-yolo.onnx"
        const val MODEL_FILE = "translation-manga-yolo.onnx"
        const val INPUT_SIZE = 640
        const val CONF_THRESHOLD = 0.25f
        const val LETTERBOX_GRAY = 114
        const val MAX_ASPECT_RATIO = 3.0f
        const val MIN_CHUNK_HEIGHT = 800
        const val MAX_CHUNK_HEIGHT = 1500
        const val OVERLAP = 200
        const val GUTTER_MIN = 10
        const val WHITE_THRESHOLD = 245.0
        const val BLACK_ROW = 15.0
    }
}
