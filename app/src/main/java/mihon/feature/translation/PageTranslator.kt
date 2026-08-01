package mihon.feature.translation

import android.content.Context
import android.graphics.Bitmap
import mihon.feature.translation.detect.BubbleDetector
import mihon.feature.translation.model.BubbleBox
import mihon.feature.translation.model.BubbleText
import mihon.feature.translation.model.TranslatedBubble
import mihon.feature.translation.ocr.BubbleTextRecognizer
import mihon.feature.translation.provider.BubbleTranslation
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider
import mihon.feature.translation.provider.TranslationProviders
import mihon.feature.translation.render.BubbleRenderer
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Translates a single manga page end to end: detect bubbles, read them, translate, repaint.
 *
 * Detection and rendering are always on-device. Only the text leaves the device, and only to the
 * provider the user picked.
 */
class PageTranslator(
    private val context: Context,
    private val preferences: TranslationPreferences,
) {

    private val detector by lazy { BubbleDetector(context) }
    private val recognizer by lazy { BubbleTextRecognizer() }
    private val renderer by lazy { BubbleRenderer(context) }

    /** Thrown when the page simply has no dialogue to translate — not an error worth surfacing. */
    class NothingToTranslate : Exception("No speech bubbles found")

    /**
     * @return a new bitmap with translated dialogue; [source] is never recycled here
     * @throws NothingToTranslate when no bubble yielded usable text
     */
    suspend fun translate(source: Bitmap): Bitmap = withIOContext {
        val detected = detector.detect(source)
        val boxes = detected.filterNot { it.isEdgeSliver(source.width) }.inReadingOrder()
        if (boxes.size < detected.size) {
            logcat { "Dropped ${detected.size - boxes.size} edge-sliver box(es)" }
        }
        if (boxes.isEmpty()) throw NothingToTranslate()
        logcat { "Detected ${boxes.size} bubbles on ${source.width}x${source.height} page" }

        val provider = currentProvider()
        val translationContext = TranslationContext(
            sourceLanguage = preferences.sourceLanguage.get(),
            targetLanguage = preferences.targetLanguage.get(),
            styleHint = preferences.styleHint.get(),
        )

        // Glyph geometry is collected regardless of who does the reading, because the renderer needs it
        // to mask strokes rather than whole bubbles. It is cheap and fully local.
        val recognized = recognizer.recognize(source, boxes, translationContext.sourceLanguage)

        val answered = if (provider.supportsVisionOcr) {
            provider.ocrAndTranslate(source, boxes, translationContext)
        } else {
            translateFromOcr(provider, boxes, recognized, translationContext)
        }
        val translations = realignToOcr(answered, recognized)

        val bubbles = boxes.mapIndexedNotNull { index, box ->
            val translated = translations.getOrNull(index)?.trim().orEmpty()
            if (translated.isEmpty()) return@mapIndexedNotNull null
            if (!plausibleForTarget(translated, translationContext.targetLanguage)) {
                // A CJK string offered as a Vietnamese translation is never a translation — it is the
                // model echoing source text (typically a logo or decorated title it could not render
                // into the target). Drawing it would stamp Chinese into the reader's page.
                logcat { "Dropping non-target-script translation for bubble ${index + 1}" }
                return@mapIndexedNotNull null
            }
            val ocrText = recognized.getOrNull(index)?.text.orEmpty()
            if (isUntranslated(ocrText, translated)) {
                // Providers echo the input when they cannot translate it, which is what happens to
                // misread sound effects ("MHM~~" recognised as "WAWHW"). Erasing hand-drawn lettering
                // only to stamp the same garbled string back is strictly worse than leaving the
                // artwork alone.
                logcat { "Dropping unchanged translation for bubble ${index + 1}" }
                return@mapIndexedNotNull null
            }
            TranslatedBubble(
                box = box,
                original = recognized.getOrNull(index)?.text.orEmpty(),
                translated = translated,
                lines = recognized.getOrNull(index)?.lines.orEmpty(),
            )
        }

        if (bubbles.isEmpty()) throw NothingToTranslate()
        logcat { "Rendering ${bubbles.size}/${boxes.size} translated bubbles" }

        renderer.render(source, bubbles, preferences.font.get())
    }

    /**
     * Sends only the bubbles that produced text, then scatters the results back to their original
     * indices so blank bubbles stay blank instead of shifting every later translation up by one.
     */
    private suspend fun translateFromOcr(
        provider: TranslationProvider,
        boxes: List<BubbleBox>,
        recognized: List<BubbleText>,
        translationContext: TranslationContext,
    ): List<BubbleTranslation> {
        val blank = BubbleTranslation("", "")
        val indexed = recognized.withIndex().filter { it.value.text.isNotBlank() }
        if (indexed.isEmpty()) return List(boxes.size) { blank }

        val translated = provider.translateLines(indexed.map { it.value.text }, translationContext)
        val result = MutableList(boxes.size) { blank }
        indexed.forEachIndexed { position, entry ->
            // The provider was given the OCR text, so that is the source for this slot regardless of
            // what it echoed back.
            result[entry.index] = translated.getOrNull(position)
                ?.copy(source = entry.value.text)
                ?: blank
        }
        return result
    }

    /**
     * Puts each translation in the bubble whose text it actually corresponds to.
     *
     * Ids make placement deterministic, but they cannot make the model's *reading* correct: on a
     * webtoon strip twenty thousand pixels tall, given a downscaled page and a list of coordinates, it
     * will sometimes attach the right translation to the wrong id. What the reader then sees is one
     * character speaking another's line — indistinguishable from a bad translation, and worse, because
     * the dialogue no longer follows.
     *
     * On-device OCR is the arbiter. It is run per bubble on a native-resolution crop, so its text is
     * bound to the correct box by construction; the model's echoed source text can therefore be
     * matched against it. Where a permutation agrees with OCR better than the model's own ordering
     * did, that permutation is the truth.
     *
     * Deliberately conservative: a swap has to be a clear improvement on both entries involved before
     * it is taken, because OCR on stylised lettering is itself imperfect and a wrong "correction"
     * would introduce exactly the fault it is meant to remove. When in doubt nothing moves.
     */
    private fun realignToOcr(
        answered: List<BubbleTranslation>,
        recognized: List<BubbleText>,
    ): List<String> {
        val current = answered.map { it.translation }
        if (answered.size < 2 || answered.size != recognized.size) return current
        // Nothing to match against if the provider did not echo what it read.
        if (answered.none { it.source.isNotBlank() }) return current

        val ocr = recognized.map { normalizeForMatch(it.text) }
        val said = answered.map { normalizeForMatch(it.source) }

        val order = IntArray(answered.size) { it }
        var moved = 0

        // Pairwise swaps only. A full assignment solve would chase permutations that similarity this
        // noisy cannot justify; almost every real failure is two bubbles trading places.
        for (i in answered.indices) {
            for (j in i + 1 until answered.size) {
                val a = order[i]
                val b = order[j]
                if (said[a].isBlank() || said[b].isBlank()) continue
                if (ocr[i].isBlank() || ocr[j].isBlank()) continue

                val keep = similarity(said[a], ocr[i]) + similarity(said[b], ocr[j])
                val swap = similarity(said[b], ocr[i]) + similarity(said[a], ocr[j])
                if (swap < keep + SWAP_MARGIN) continue
                if (similarity(said[b], ocr[i]) < MIN_MATCH || similarity(said[a], ocr[j]) < MIN_MATCH) continue

                order[i] = b
                order[j] = a
                moved++
            }
        }

        if (moved > 0) {
            logcat { "Re-keyed $moved bubble translation(s) to match on-device OCR" }
        }

        // Second pass: relocation. A swap can only fix two bubbles that traded places; on pages with
        // non-dialogue lettering (credits, promos, logos) the model produces longer shifts — bubble A
        // wearing B's text wearing C's. Any translation whose echoed source clearly disagrees with the
        // OCR of the bubble it sits in is misplaced; if its source matches exactly one other bubble
        // strongly, and that bubble's own entry is itself misplaced or empty, it moves there. When no
        // such home exists the translation is dropped outright: wrong dialogue in a bubble reads as a
        // worse defect than an untranslated bubble.
        val translations = MutableList(order.size) { answered[order[it]].translation }
        val sourceAt = List(order.size) { said[order[it]] }
        // OCR shorter than a few characters is not evidence — a lone misread glyph must not get a
        // real translation dropped on its account.
        fun misplacedAt(i: Int): Boolean =
            sourceAt[i].isNotBlank() && ocr[i].length >= MIN_OCR_EVIDENCE &&
                similarity(sourceAt[i], ocr[i]) < DROP_BELOW

        val result = translations.toMutableList()
        val claimed = mutableSetOf<Int>()
        var relocated = 0
        var dropped = 0
        for (i in order.indices) {
            if (translations[i].isBlank() || !misplacedAt(i)) continue
            val home = order.indices
                .filter { it != i && it !in claimed && ocr[it].isNotBlank() }
                .maxByOrNull { similarity(sourceAt[i], ocr[it]) }
            val score = home?.let { similarity(sourceAt[i], ocr[it]) } ?: 0f
            if (home != null && score >= RELOCATE_MIN && (sourceAt[home].isBlank() || misplacedAt(home))) {
                result[home] = translations[i]
                claimed += home
                relocated++
            } else {
                dropped++
            }
            if (i !in claimed) result[i] = ""
        }
        if (relocated > 0 || dropped > 0) {
            logcat { "Relocated $relocated and dropped $dropped misplaced translation(s)" }
        }
        return result
    }

    /**
     * Symmetric similarity in 0..1. Word overlap when both sides have enough words to make that
     * meaningful; character-bigram Dice otherwise, which is what makes CJK strings — no spaces, so
     * "one word" however long — comparable at all.
     */
    private fun similarity(a: String, b: String): Float {
        val left = a.split(' ').filter { it.length > 1 }.toSet()
        val right = b.split(' ').filter { it.length > 1 }.toSet()
        if (left.size >= 2 && right.size >= 2) {
            val shared = left.count { it in right }
            return 2f * shared / (left.size + right.size)
        }
        return bigramDice(a.replace(" ", ""), b.replace(" ", ""))
    }

    private fun bigramDice(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2) return if (a.isNotEmpty() && a == b) 1f else 0f
        val left = HashSet<Int>(a.length)
        val right = HashSet<Int>(b.length)
        for (i in 0 until a.length - 1) left += a[i].code * 0x10000 + a[i + 1].code
        for (i in 0 until b.length - 1) right += b[i].code * 0x10000 + b[i + 1].code
        val shared = left.count { it in right }
        return 2f * shared / (left.size + right.size)
    }

    /**
     * True when the "translation" is just the source text handed back.
     *
     * Only applied to short strings. A long line that survives translation unchanged does not happen
     * in practice, whereas a legitimately identical rendering of a short one does — a name, a number,
     * an interjection that reads the same in both languages — and dropping those would leave real
     * dialogue untranslated.
     */
    private fun isUntranslated(source: String, translated: String): Boolean {
        if (source.isBlank()) return false
        val a = normalizeForMatch(source)
        val b = normalizeForMatch(translated)
        return a == b && b.length <= MAX_ECHO_LENGTH
    }

    /**
     * True when [text] is written in the script the reader asked for — or close enough. Latin-target
     * translations that come back mostly CJK are the model refusing (or failing) to translate, and
     * must never reach the renderer.
     */
    private fun plausibleForTarget(text: String, targetLanguage: String): Boolean {
        if (targetLanguage in CJK_TARGETS) return true
        var letters = 0
        var cjk = 0
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            letters++
            val cp = ch.code
            if (cp in 0x1100..0x11FF || // Hangul jamo
                cp in 0x3040..0x30FF || // Hiragana + katakana
                cp in 0x3400..0x9FFF || // Han
                cp in 0xAC00..0xD7AF || // Hangul syllables
                cp in 0xF900..0xFAFF // Han compatibility
            ) {
                cjk++
            }
        }
        return letters == 0 || cjk * 10 < letters * 3
    }

    private fun normalizeForMatch(text: String): String =
        text.lowercase().replace(NON_WORD, " ").replace(WHITESPACE, " ").trim()

    /**
     * Orders bubbles the way they are read: down the page, then across each band.
     *
     * The detector emits boxes in descending confidence, which is effectively random on the page. The
     * prompt asks the model to read them "in the listed order", so handing it a scrambled list makes
     * it work against the natural flow of the dialogue and invites it to re-order the reply to match
     * what it sees — the exact thing that used to shift translations into neighbouring bubbles.
     *
     * Boxes within one band are treated as the same row so a left-hand bubble sitting a few pixels
     * lower than its neighbour does not jump ahead of it.
     */
    private fun List<BubbleBox>.inReadingOrder(): List<BubbleBox> {
        if (size < 2) return this
        val band = maxOf(MIN_READING_BAND, (sumOf { it.bottom - it.top } / size) / 2)
        return sortedWith(compareBy({ it.top / band }, { it.left }))
    }

    /**
     * A bubble mostly cropped off by the page edge — webtoon strips routinely cut a bubble in half at
     * a page boundary, and its other half lives on the neighbouring page.
     *
     * The visible sliver is dropped before the provider ever sees it. It has no readable text of its
     * own, so the model invents some from context, and the renderer then letters that invention into
     * a strip a few dozen pixels wide: the reader sees clipped orphan glyphs hanging on the page edge.
     */
    private fun BubbleBox.isEdgeSliver(pageWidth: Int): Boolean {
        val touchesEdge = left <= EDGE_SLOP || right >= pageWidth - EDGE_SLOP
        return touchesEdge && width < maxOf(SLIVER_MIN_WIDTH, (pageWidth * SLIVER_WIDTH_RATIO).toInt())
    }

    fun currentProvider(): TranslationProvider = TranslationProviders.current(preferences)

    fun close() {
        runCatching { detector.close() }
    }

    private companion object {
        /** Floor for the row-banding height, so a page of tiny boxes still bands sensibly. */
        const val MIN_READING_BAND = 24

        /** How much better a swap must score before it is trusted over the model's own ordering. */
        const val SWAP_MARGIN = 0.30f
        /** Neither half of a swap may be a weak match, however good the pair looks together. */
        const val MIN_MATCH = 0.34f
        /** Below this, a translation's echoed source plainly is not what its bubble says. */
        const val DROP_BELOW = 0.15f
        /** Normalised OCR text shorter than this cannot convict a translation of being misplaced. */
        const val MIN_OCR_EVIDENCE = 4
        /** A relocation target must match this strongly before a translation is moved into it. */
        const val RELOCATE_MIN = 0.50f
        /** Targets whose native scripts are CJK; the script sanity check does not apply to them. */
        val CJK_TARGETS = setOf("zh", "ja", "ko")
        /** Longest echoed string still treated as "the provider gave up" rather than a real match. */
        const val MAX_ECHO_LENGTH = 24

        /** Boxes narrower than this fraction of the page that hug its edge are cropped remnants. */
        const val SLIVER_WIDTH_RATIO = 0.10f
        const val SLIVER_MIN_WIDTH = 48
        const val EDGE_SLOP = 2

        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        val WHITESPACE = Regex("\\s+")
    }
}
