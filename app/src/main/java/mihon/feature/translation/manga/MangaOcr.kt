package mihon.feature.translation.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device port of `kha-white/manga-ocr-base`: ViT encoder + BERT decoder greedy generate.
 *
 * Preprocess, special tokens, max_length=300 and post_process match `manga_ocr.ocr.MangaOcr`.
 */
internal class MangaOcr(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val vocab: List<String> by lazy {
        context.assets.open(VOCAB_ASSET).bufferedReader().use { it.readLines() }
    }
    private val encoder: OrtSession by lazy { openWorkingEncoder() }
    private val decoder: OrtSession by lazy { open(decoderAsset(), DECODER_FILE) }

    fun recognize(source: Bitmap): String = runCatching {
        val hidden = encode(source)
        val tokens = ArrayList<Int>(32)
        tokens += MangaOcrPostProcess.DECODER_START
        val hiddenBuffer = FloatBuffer.allocate(hidden.size)
        hiddenBuffer.put(hidden)
        hiddenBuffer.rewind()
        OnnxTensor.createTensor(
            env,
            hiddenBuffer,
            longArrayOf(1, hidden.size.toLong() / HIDDEN, HIDDEN.toLong()),
        ).use { encoderStates ->
            for (step in 0 until MangaOcrPostProcess.MAX_LENGTH - 1) {
                val ids = LongBuffer.allocate(tokens.size)
                tokens.forEach { ids.put(it.toLong()) }
                ids.rewind()
                OnnxTensor.createTensor(env, ids, longArrayOf(1, tokens.size.toLong())).use { inputIds ->
                    decoder.run(
                        mapOf(
                            "encoder_hidden_states" to encoderStates,
                            "decoder_input_ids" to inputIds,
                        ),
                    ).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (output[0] as OnnxTensor).value as Array<Array<FloatArray>>
                        val last = logits[0].last()
                        var best = 0
                        var bestScore = Float.NEGATIVE_INFINITY
                        for (i in last.indices) {
                            if (last[i] > bestScore) {
                                bestScore = last[i]
                                best = i
                            }
                        }
                        if (best == MangaOcrPostProcess.EOS) return@runCatching finish(tokens)
                        tokens += best
                    }
                }
            }
        }
        finish(tokens)
    }.onFailure {
        logcat { "manga-ocr failed: ${it.message}" }
    }.getOrDefault("")

    private fun finish(tokens: List<Int>): String {
        val decoded = MangaOcrPostProcess.decodeTokens(tokens, vocab)
        return MangaOcrPostProcess.apply(decoded)
    }

    private fun encode(source: Bitmap): FloatArray {
        val gray = toGray(source)
        val resized = Bitmap.createScaledBitmap(gray, INPUT, INPUT, true)
        try {
            val pixels = IntArray(INPUT * INPUT)
            resized.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
            val buffer = FloatBuffer.allocate(3 * INPUT * INPUT)
            val plane = FloatArray(INPUT * INPUT)
            for (i in pixels.indices) {
                val y = MangaPixels.gray(pixels[i])
                plane[i] = y / 127.5f - 1f
            }
            repeat(3) { buffer.put(plane) }
            buffer.rewind()
            OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { tensor ->
                encoder.run(mapOf(encoder.inputNames.first() to tensor)).use { output ->
                    val tensorOut = output[0] as OnnxTensor
                    val value = tensorOut.floatBuffer
                    val copy = FloatArray(value.remaining())
                    value.get(copy)
                    return copy
                }
            }
        } finally {
            if (resized !== gray) resized.recycle()
            if (gray !== source) gray.recycle()
        }
    }

    private fun toGray(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (i in pixels.indices) {
            val y = MangaPixels.gray(pixels[i])
            pixels[i] = MangaPixels.rgb(y, y, y)
        }
        out.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return out
    }

    private fun openWorkingEncoder(): OrtSession {
        val candidates = listOf(ENCODER_INT8_ASSET to ENCODER_FILE, ENCODER_FP32_ASSET to ENCODER_FP32_FILE)
            .filter { (asset, _) -> assetExists(asset) }
        var last: Throwable? = null
        for ((asset, fileName) in candidates) {
            try {
                return open(asset, fileName)
            } catch (error: Throwable) {
                last = error
                logcat { "manga-ocr encoder $asset unusable: ${error.message}" }
            }
        }
        throw last ?: IllegalStateException("Missing manga-ocr encoder ONNX")
    }

    private fun open(assetPath: String, fileName: String): OrtSession {
        val file = MangaOnnxStore.extract(context, assetPath, fileName)
        return try {
            env.createSession(file.absolutePath)
        } catch (first: Throwable) {
            if (first.message?.contains("ORT_NOT_IMPLEMENTED") == true) throw first
            logcat { "manga-ocr $assetPath failed: ${first.message}; re-extracting" }
            file.delete()
            val retry = MangaOnnxStore.extract(context, assetPath, fileName)
            env.createSession(retry.absolutePath)
        }.also {
            logcat { "manga-ocr ready $assetPath inputs=${it.inputNames} outputs=${it.outputNames}" }
        }
    }

    private fun decoderAsset(): String = firstExisting(DECODER_INT8_ASSET, DECODER_FP32_ASSET)
    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).close(); true }.getOrDefault(false)

    private fun firstExisting(vararg paths: String): String {
        for (path in paths) {
            val exists = runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
            if (exists) return path
        }
        error("Missing manga-ocr ONNX in assets (${paths.joinToString()})")
    }

    fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }

    private companion object {
        const val INPUT = 224
        const val HIDDEN = 768
        const val VOCAB_ASSET = "translation/manga-ocr/vocab.txt"
        const val ENCODER_INT8_ASSET = "translation/manga-ocr/encoder.int8.onnx"
        const val ENCODER_FP32_ASSET = "translation/manga-ocr/encoder.onnx"
        const val DECODER_INT8_ASSET = "translation/manga-ocr/decoder.int8.onnx"
        const val DECODER_FP32_ASSET = "translation/manga-ocr/decoder.onnx"
        const val ENCODER_FILE = "translation-manga-ocr-encoder.onnx"
        const val ENCODER_FP32_FILE = "translation-manga-ocr-encoder-fp32.onnx"
        const val DECODER_FILE = "translation-manga-ocr-decoder.onnx"
    }
}
