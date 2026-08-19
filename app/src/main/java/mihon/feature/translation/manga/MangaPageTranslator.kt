package mihon.feature.translation.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.translation.provider.GeminiTranslationProvider
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider
import tachiyomi.core.common.util.system.logcat

/**
 * Page pipeline from Manga-Translator `app.process_single_image`:
 * YOLO (+ black-bubble fallback) → manga-ocr → process_bubble_auto → Gemini batch → add_text.
 *
 * Returns null when the page is not Japanese dialogue, so the caller can fall back to the original
 * Kotori path without painting over a manhwa slice.
 */
internal class MangaPageTranslator(
    private val context: Context,
    private val localWork: Mutex,
) {

    private val detector by lazy { MangaYoloDetector(context) }
    private val ocr by lazy { MangaOcr(context) }
    private val typeface: Typeface by lazy {
        runCatching { Typeface.createFromAsset(context.assets, "translation/HL-Comic.ttf") }
            .getOrDefault(Typeface.DEFAULT_BOLD)
    }

    suspend fun translate(
        source: Bitmap,
        context: TranslationContext,
        provider: TranslationProvider,
        diagnosticLabel: String,
    ): Bitmap? {
        val started = System.currentTimeMillis()
        data class Work(
            val box: MangaBlobs.Box,
            val filled: Bitmap,
            val contourLeft: Int,
            val contourTop: Int,
            val contourRight: Int,
            val contourBottom: Int,
            val isDark: Boolean,
            val ocr: String,
        )

        var work: List<Work> = emptyList()
        try {
            work = localWork.withLock {
                val boxes = detector.detect(source)
                if (boxes.isEmpty()) {
                    logcat { "$diagnosticLabel manga: 0 bubbles" }
                    return@withLock emptyList()
                }
                boxes.mapNotNull { box ->
                    val w = (box.right - box.left).coerceAtLeast(1)
                    val h = (box.bottom - box.top).coerceAtLeast(1)
                    val crop = Bitmap.createBitmap(source, box.left, box.top, w, h)
                    val text = ocr.recognize(crop)
                    val pixels = IntArray(w * h)
                    crop.getPixels(pixels, 0, w, 0, 0, w, h)
                    val filled = MangaBlobs.processAuto(pixels, w, h, forceDark = box.isDark)
                    crop.setPixels(filled.pixels, 0, w, 0, 0, w, h)
                    Work(
                        box = box,
                        filled = crop,
                        contourLeft = filled.contourLeft,
                        contourTop = filled.contourTop,
                        contourRight = filled.contourRight,
                        contourBottom = filled.contourBottom,
                        isDark = filled.isDark,
                        ocr = text,
                    )
                }
            }
            if (work.isEmpty()) return null
            if (!MangaPipeline.hasJapaneseDialogue(work.map { it.ocr })) {
                logcat { "$diagnosticLabel manga: no Japanese OCR; falling back" }
                return null
            }

            val texts = work.map { it.ocr }
            val translations = translateTexts(provider, texts, context)
            val output = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(output)
            work.zip(translations).forEach { (item, translated) ->
                val text = translated.ifBlank { item.ocr }
                MangaLetterer.addText(
                    image = item.filled,
                    text = text,
                    typeface = typeface,
                    contourLeft = item.contourLeft,
                    contourTop = item.contourTop,
                    contourWidth = (item.contourRight - item.contourLeft).coerceAtLeast(1),
                    contourHeight = (item.contourBottom - item.contourTop).coerceAtLeast(1),
                    lightText = item.isDark,
                )
                canvas.drawBitmap(
                    item.filled,
                    null,
                    Rect(item.box.left, item.box.top, item.box.right, item.box.bottom),
                    null,
                )
            }
            logcat {
                "$diagnosticLabel manga: ${work.size} bubbles in ${System.currentTimeMillis() - started}ms"
            }
            return output
        } finally {
            work.forEach { item ->
                if (!item.filled.isRecycled) item.filled.recycle()
            }
        }
    }

    private suspend fun translateTexts(
        provider: TranslationProvider,
        texts: List<String>,
        context: TranslationContext,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (provider is GeminiTranslationProvider) {
            val batch = provider.translateMangaBatch(texts, context)
            if (batch != null && batch.size == texts.size) return batch
        }
        val lines = provider.translateLines(texts, context)
        return List(texts.size) { index -> lines.getOrNull(index)?.translation.orEmpty() }
    }

    fun close() {
        runCatching { detector.close() }
        runCatching { ocr.close() }
    }
}
