package mihon.feature.translation.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.translation.TranslationRenderStyle
import mihon.feature.translation.detect.SimplePageReader
import mihon.feature.translation.model.BubbleBox
import mihon.feature.translation.model.TranslatedBubble
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider
import mihon.feature.translation.render.BubbleRenderer
import mihon.feature.translation.render.FlatBackgroundGuard
import tachiyomi.core.common.util.system.logcat

/**
 * Page pipeline from Manga-Translator `app.process_single_image`:
 * YOLO (+ black-bubble fallback) → read the balloons → `process_bubble_auto` → `add_text`.
 *
 * Two deliberate departures from the Python original, both because a phone is not a workstation and
 * a reader is not a batch job.
 *
 * **The provider does the reading.** The reference implementation reads each balloon with Chrome
 * Lens or manga-ocr — a server round trip and a 130 MB transformer respectively. The transformer was
 * measured here at 25 s *per balloon*, a 23-balloon page taking 590 s, because its decoder has no
 * key/value cache and re-runs the whole token prefix at every step. The provider is already being
 * called for the translation, already sees the artwork, and reads whatever language the page happens
 * to be in; asking it to read as well costs one call for the whole page and removes the
 * source-language question entirely.
 *
 * **Lettering outside a balloon is picked up too, where it can be erased honestly.** The Python
 * version letters only what YOLO finds, so a page whose narration is set straight onto the artwork
 * comes back mostly untranslated. Those regions are found by reading the page and are included in
 * the same provider call — but only where the tone behind them is uniform enough to repaint, since
 * over hatching or a face the repaint is a slab of the wrong colour across the drawing.
 */
internal class MangaPageTranslator(
    private val context: Context,
    private val localWork: Mutex,
    private val pageReader: SimplePageReader,
    private val renderer: BubbleRenderer,
    private val fontName: () -> String,
) {

    /** What the manga port managed to do with a page. */
    sealed interface Outcome {
        /** Rendered artwork with the dialogue replaced. */
        data class Rendered(val bitmap: Bitmap) : Outcome

        /** The page was found and read, and it holds no dialogue. Authoritative. */
        data object NoDialogue : Outcome

        /** This page is not for this pipeline; the caller's original path should take it. */
        data object NotHandled : Outcome
    }

    private val detector by lazy { MangaYoloDetector(context) }
    private val typeface: Typeface by lazy {
        runCatching { Typeface.createFromAsset(context.assets, "translation/HL-Comic.ttf") }
            .getOrDefault(Typeface.DEFAULT_BOLD)
    }

    private class Work(
        val box: MangaBlobs.Box,
        val filled: Bitmap,
        val contourLeft: Int,
        val contourTop: Int,
        val contourRight: Int,
        val contourBottom: Int,
        val isDark: Boolean,
        val text: String,
    )

    suspend fun translate(
        source: Bitmap,
        translationContext: TranslationContext,
        provider: TranslationProvider,
        diagnosticLabel: String,
    ): Outcome = coroutineScope {
        if (!provider.supportsVisionOcr) return@coroutineScope Outcome.NotHandled

        val startedAt = System.currentTimeMillis()

        // The ONNX session is single-lane, so detection holds the caller's lock.
        val balloons = localWork.withLock { detector.detect(source) }
        val detectedAt = System.currentTimeMillis()

        // Reading the page and asking the provider about the balloons are the two expensive steps
        // and they do not need each other, so they run at the same time: one is a network wait, the
        // other is the on-device recogniser. Done in sequence they simply added up — measured at
        // 6 s + 14 s on a 2069x2880 raw. The read still takes the lock, because parallel ML Kit was
        // tried here once and came out three times slower than serial.
        val readAhead = async(Dispatchers.Default) {
            localWork.withLock { pageReader.read(source, translationContext.sourceLanguage) }
        }
        val balloonAnswers = if (balloons.isEmpty()) {
            emptyList()
        } else {
            provider.ocrAndTranslate(
                source,
                balloons.map { box -> BubbleBox(box.left, box.top, box.right, box.bottom, box.confidence) },
                translationContext,
            )
        }
        val read = readAhead.await()
        val readAt = System.currentTimeMillis()

        val free = read.boxes.indices.filter { index ->
            val box = read.boxes[index]
            box.isUsable() &&
                balloons.none { balloon -> containedFraction(box, balloon) >= BALLOON_OWNS_REGION } &&
                canRepaint(source, box, read.texts[index].lines.map { it.rect })
        }
        if (balloons.isEmpty() && free.isEmpty()) {
            logcat { "$diagnosticLabel manga: nothing to letter; using original path" }
            return@coroutineScope Outcome.NotHandled
        }

        // Lettering outside a balloon is translated from the text the page read already produced,
        // not by sending the artwork a second time.
        //
        // Folding these regions into the first call would mean waiting for the read before asking
        // about the balloons, which is the sequencing above exists to avoid; sending a second *image*
        // call instead cost 7-18 s a page, as much as the whole rest of the page. A text call carries
        // no image at all and the recogniser has already read these regions — they are narration set
        // on flat tone, which is the case on-device OCR handles well. A page with no free lettering,
        // which is most of them, makes no second call at all.
        val freeTexts = free.map { read.texts[it].text }
        val freeAnswers = if (free.isEmpty() || freeTexts.all { it.isBlank() }) {
            emptyList()
        } else {
            provider.translateLines(freeTexts, translationContext)
        }
        val narratedAt = System.currentTimeMillis()

        // A balloon the model answered blank is artwork, a publisher credit or a sound effect. The
        // Python original always fills, because its OCR either read text or returned nothing at all;
        // here a blank answer must leave the balloon untouched, or every unlettered oval on the page
        // is flooded with its own background colour and the page comes back visibly scrubbed.
        val speaking = balloons.indices.filter { index ->
            balloonAnswers.getOrNull(index)?.translation?.isNotBlank() == true
        }
        val freeBubbles = free.mapIndexedNotNull { position, index ->
            val translated = freeAnswers.getOrNull(position)?.translation?.trim().orEmpty()
            val original = read.texts[index].text
            if (translated.isBlank() || echoesSource(original, translated)) {
                null
            } else {
                TranslatedBubble(
                    box = read.boxes[index],
                    original = original,
                    translated = translated,
                    lines = read.texts[index].lines,
                )
            }
        }

        if (speaking.isEmpty() && freeBubbles.isEmpty()) {
            logcat { "$diagnosticLabel manga: ${balloons.size + free.size} region(s), none with dialogue" }
            return@coroutineScope Outcome.NoDialogue
        }

        var page: Bitmap? = null
        if (speaking.isNotEmpty()) {
            page = letterBalloons(
                source,
                speaking.map { balloons[it] },
                speaking.map { balloonAnswers[it].translation },
                diagnosticLabel,
            )
        }
        val filledAt = System.currentTimeMillis()

        if (freeBubbles.isNotEmpty()) {
            val base = page ?: source
            val withNarration = renderer.render(
                base,
                freeBubbles,
                fontName(),
                style = TranslationRenderStyle.SIMPLE,
            )
            if (base !== source) base.recycle()
            page = withNarration
        }

        val doneAt = System.currentTimeMillis()
        logcat {
            "$diagnosticLabel manga: ${speaking.size}/${balloons.size} balloon(s) + " +
                "${freeBubbles.size}/${free.size} free region(s) — " +
                "detect=${detectedAt - startedAt}ms read+provider=${readAt - detectedAt}ms " +
                "narration-tl=${narratedAt - readAt}ms balloons=${filledAt - narratedAt}ms " +
                "narration=${doneAt - filledAt}ms total=${doneAt - startedAt}ms"
        }
        page?.let { Outcome.Rendered(it) } ?: Outcome.NoDialogue
    }

    /**
     * `process_bubble_auto` + `add_text` for every speaking balloon.
     *
     * @return the page with those balloons replaced, or null when none could be lettered legibly
     */
    private suspend fun letterBalloons(
        source: Bitmap,
        boxes: List<MangaBlobs.Box>,
        texts: List<String>,
        diagnosticLabel: String,
    ): Bitmap? {
        var work: List<Work> = emptyList()
        try {
            work = fillBalloons(source, boxes, texts)
            val output = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(output)
            val (minFont, maxFont) = MangaTextWrap.boundsFor(source.width)
            var lettered = 0
            for (item in work) {
                val ok = MangaLetterer.addText(
                    image = item.filled,
                    text = item.text,
                    typeface = typeface,
                    contourLeft = item.contourLeft,
                    contourTop = item.contourTop,
                    contourWidth = (item.contourRight - item.contourLeft).coerceAtLeast(1),
                    contourHeight = (item.contourBottom - item.contourTop).coerceAtLeast(1),
                    lightText = item.isDark,
                    minFont = minFont,
                    maxFont = maxFont,
                )
                // The fill and the lettering are one edit: without the text there is no reason to
                // have wiped the balloon. A region too small to hold its translation legibly —
                // usually a fragment of a stylised title the detector clipped — is left as drawn,
                // which is how a page stops coming back with grains of unreadable type on it.
                if (!ok) {
                    logcat {
                        "$diagnosticLabel manga: leaving ${item.box.left},${item.box.top} as-is — " +
                            "${item.contourRight - item.contourLeft}x" +
                            "${item.contourBottom - item.contourTop} cannot hold the translation"
                    }
                    continue
                }
                lettered++
                canvas.drawBitmap(
                    item.filled,
                    null,
                    Rect(item.box.left, item.box.top, item.box.right, item.box.bottom),
                    null,
                )
            }
            if (lettered == 0) {
                output.recycle()
                return null
            }
            return output
        } finally {
            work.forEach { item -> if (!item.filled.isRecycled) item.filled.recycle() }
        }
    }

    /**
     * `process_bubble_auto` for every speaking balloon, in parallel.
     *
     * Each balloon is an independent crop, so the flood fill and colour vote parallelise cleanly
     * across cores. Cropping itself stays on this thread: `Bitmap.createBitmap` against a shared
     * source is not safe to call concurrently.
     */
    private suspend fun fillBalloons(
        source: Bitmap,
        boxes: List<MangaBlobs.Box>,
        texts: List<String>,
    ): List<Work> = coroutineScope {
        val crops = boxes.map { box ->
            val w = (box.right - box.left).coerceAtLeast(1)
            val h = (box.bottom - box.top).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(source, box.left, box.top, w, h)
            // createBitmap may hand back an immutable view; the fill writes into it.
            val crop = if (cropped.isMutable) {
                cropped
            } else {
                cropped.copy(Bitmap.Config.ARGB_8888, true).also { cropped.recycle() }
            }
            Triple(box, crop, w to h)
        }
        crops.mapIndexed { index, (box, crop, size) ->
            async(Dispatchers.Default) {
                val (w, h) = size
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
                    text = texts[index],
                )
            }
        }.awaitAll()
    }

    /** True when the tone behind this lettering is uniform enough to repaint. */
    private fun canRepaint(source: Bitmap, box: BubbleBox, lines: List<Rect>): Boolean {
        if (lines.isEmpty()) return false
        val left = box.left.coerceIn(0, source.width)
        val top = box.top.coerceIn(0, source.height)
        val right = box.right.coerceIn(left, source.width)
        val bottom = box.bottom.coerceIn(top, source.height)
        val w = right - left
        val h = bottom - top
        if (w < MIN_FREE_SIDE || h < MIN_FREE_SIDE) return false
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, left, top, w, h)
        return FlatBackgroundGuard.canRepaint(
            pixels,
            w,
            h,
            lines.map { line ->
                FlatBackgroundGuard.Line(
                    line.left - left,
                    line.top - top,
                    line.right - left,
                    line.bottom - top,
                )
            },
        )
    }

    /**
     * Providers echo their input when they cannot translate it, which is what happens to a misread
     * sound effect. Erasing hand-drawn lettering to stamp the same string back is worse than leaving
     * the artwork alone.
     */
    private fun echoesSource(original: String, translated: String): Boolean {
        val a = original.filter { it.isLetterOrDigit() }.lowercase()
        val b = translated.filter { it.isLetterOrDigit() }.lowercase()
        return a.isNotEmpty() && a == b
    }

    private fun containedFraction(region: BubbleBox, balloon: MangaBlobs.Box): Float {
        val left = maxOf(region.left, balloon.left)
        val top = maxOf(region.top, balloon.top)
        val right = minOf(region.right, balloon.right)
        val bottom = minOf(region.bottom, balloon.bottom)
        if (right <= left || bottom <= top) return 0f
        val area = region.width.toFloat() * region.height
        if (area <= 0f) return 0f
        return (right - left).toFloat() * (bottom - top) / area
    }

    fun close() {
        runCatching { detector.close() }
    }

    private companion object {
        /** Overlap at which a balloon is considered to already own a region of lettering. */
        const val BALLOON_OWNS_REGION = 0.6f

        /** Below this a "region" is a speck; repainting one only damages the drawing. */
        const val MIN_FREE_SIDE = 12
    }
}
