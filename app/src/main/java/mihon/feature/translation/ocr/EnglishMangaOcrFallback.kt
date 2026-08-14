package mihon.feature.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

/** Recognition-only English OCR used when ML Kit leaves a detector-confirmed crop blank. */
internal class EnglishMangaOcrFallback(private val context: Context) {

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val session = lazy {
        val model = File(context.filesDir, MODEL_FILE_NAME)
        if (!isExtractionCurrent(model)) extractModel(model)
        try {
            openSession(model)
        } catch (first: Throwable) {
            logcat { "English OCR fallback failed to load: ${first.message}; re-extracting" }
            extractModel(model)
            openSession(model)
        }
    }

    fun recognize(source: Bitmap): String = runCatching {
        val whole = recognizeLine(source)
        if (!shouldTryTwoLines(source.width, source.height)) return@runCatching whole

        val overlap = (source.height * SPLIT_OVERLAP_RATIO).toInt().coerceAtLeast(1)
        val middle = source.height / 2
        val topBottom = (middle + overlap).coerceAtMost(source.height)
        val bottomTop = (middle - overlap).coerceAtLeast(0)
        val top = Bitmap.createBitmap(source, 0, 0, source.width, topBottom)
        val bottom = Bitmap.createBitmap(source, 0, bottomTop, source.width, source.height - bottomTop)
        try {
            val first = recognizeLine(top)
            val second = recognizeLine(bottom)
            if (hasWord(first) && hasWord(second)) "$first\n$second" else whole
        } finally {
            if (top !== source) top.recycle()
            if (bottom !== source) bottom.recycle()
        }
    }.onFailure {
        logcat { "English OCR fallback failed: ${it.message}" }
    }.getOrDefault("")

    private fun recognizeLine(source: Bitmap): String {
        val resizedWidth = minOf(
            INPUT_WIDTH,
            ceil(INPUT_HEIGHT * source.width.toDouble() / source.height.coerceAtLeast(1)).toInt(),
        ).coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(source, resizedWidth, INPUT_HEIGHT, true)
        try {
            val pixels = IntArray(resizedWidth * INPUT_HEIGHT)
            resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, INPUT_HEIGHT)
            val buffer = FloatBuffer.allocate(INPUT_HEIGHT * INPUT_WIDTH)
            for (y in 0 until INPUT_HEIGHT) {
                var last = 0f
                for (x in 0 until resizedWidth) {
                    val color = pixels[y * resizedWidth + x]
                    val gray = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) /
                        1000f
                    last = gray / 127.5f - 1f
                    buffer.put(last)
                }
                repeat(INPUT_WIDTH - resizedWidth) { buffer.put(last) }
            }
            buffer.rewind()
            val shape = longArrayOf(1, 1, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
            OnnxTensor.createTensor(environment, buffer, shape).use { tensor ->
                session.value.run(mapOf(session.value.inputNames.first() to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (output[0] as OnnxTensor).value as Array<Array<FloatArray>>
                    return EnglishCtcDecoder.decode(logits[0])
                }
            }
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private fun openSession(model: File): OrtSession =
        environment.createSession(model.absolutePath).also {
            logcat { "English OCR fallback ready: inputs=${it.inputNames} outputs=${it.outputNames}" }
        }

    private fun isExtractionCurrent(model: File): Boolean {
        if (!model.isFile || model.length() == 0L) return false
        val assetLength = runCatching { context.assets.openFd(MODEL_ASSET_PATH).use { it.length } }
            .getOrElse { return false }
        return model.length() == assetLength
    }

    private fun extractModel(model: File) {
        val temporary = File(model.parentFile, "${model.name}.tmp")
        temporary.delete()
        context.assets.open(MODEL_ASSET_PATH).use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        model.delete()
        check(temporary.renameTo(model)) { "Could not install the English OCR fallback model" }
    }

    private fun shouldTryTwoLines(width: Int, height: Int): Boolean =
        height >= width * MIN_TWO_LINE_HEIGHT_RATIO

    private fun hasWord(text: String): Boolean = text.count(Char::isLetter) >= MIN_LINE_LETTERS

    fun close() {
        if (session.isInitialized()) runCatching { session.value.close() }
    }

    private companion object {
        const val MODEL_ASSET_PATH = "translation/english-recognizer.onnx"
        const val MODEL_FILE_NAME = "translation-english-recognizer.onnx"
        const val INPUT_HEIGHT = 64
        const val INPUT_WIDTH = 256
        const val MIN_TWO_LINE_HEIGHT_RATIO = 0.55f
        const val SPLIT_OVERLAP_RATIO = 0.08f
        const val MIN_LINE_LETTERS = 2
    }
}
