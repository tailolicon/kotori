package mihon.feature.translation.detect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import mihon.feature.translation.ShortDialogueNormalizer
import mihon.feature.translation.model.BubbleBox
import mihon.feature.translation.model.BubbleText
import mihon.feature.translation.model.TextLineBox
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads a page once and reports where the dialogue is and what it says.
 *
 * This is the whole of the input the simple renderer needs, and reading the page is the only way to
 * obtain it — which is the point. The bubble-aware pipeline arrives at the same two facts by three
 * stages: an ONNX model proposes speech balloons, ML Kit reads the page to find the lettering the
 * model cannot see, and then every proposed region is cropped and read *again*, with a cascade of
 * contrast, context and deskew retries behind it. Measured on an 800x14634 strip that came to
 * 10.6s + 38.4s + 79.5s, and the third stage is re-reading text the second stage had already read.
 *
 * Here the page is read once. Lines are grouped into paragraphs by geometry, which is what the
 * balloon boxes were mostly being used for anyway, and the result carries both the recognised text
 * and the line rectangles the renderer erases and letters into.
 *
 * Blocking; call from a background dispatcher.
 */
class SimplePageReader {

    /**
     * @param boxes one region per paragraph of lettering, in reading order
     * @param texts the recognised lines for the box at the same index
     * @param language the script the page turned out to be written in
     */
    data class Result(
        val boxes: List<BubbleBox>,
        val texts: List<BubbleText>,
        val language: String,
    )

    fun read(bitmap: Bitmap, sourceLanguage: String): Result {
        var lines = readPage(bitmap, sourceLanguage)
        var language = sourceLanguage

        // A page the configured recogniser barely reads is usually written in another script, not
        // blank: English aggregators mix Japanese raw chapters into the same series, and a reader
        // whose source is still on English gets Korean read as "0}2| 0.".
        //
        // "Barely" rather than "not at all" is the whole point. A recogniser pointed at the wrong
        // script does not politely return nothing — it finds letter-shaped marks in the strokes and
        // returns a handful of junk characters, which is enough for an emptiness test to conclude
        // the page was read fine. The page then goes to the translator as noise.
        val bands = max(1, (bitmap.height + BAND_HEIGHT - BAND_OVERLAP - 1) / (BAND_HEIGHT - BAND_OVERLAP))
        if (charactersIn(lines) < bands * MIN_CHARACTERS_PER_BAND) {
            val probe = probeBand(bitmap)
            val baseline = withRecognizer(sourceLanguage) {
                charactersIn(readBand(it, probe, 0, padEdges = false))
            }
            var best: String? = null
            var bestCount = 0
            for (candidate in SCRIPT_FALLBACK_ORDER) {
                if (candidate == sourceLanguage) continue
                val count = withRecognizer(candidate) {
                    charactersIn(readBand(it, probe, 0, padEdges = false))
                }
                if (count > bestCount) {
                    bestCount = count
                    best = candidate
                }
                if (count >= PROBE_CONFIDENT_CHARACTERS) break
            }
            if (probe !== bitmap) probe.recycle()
            // Only switch on a decisive win. Every recogniser reads a little of every page, so a
            // narrow margin means nothing, and a page that genuinely holds two words must not be
            // re-read four times over and then handed to whichever script guessed loudest.
            if (best != null && bestCount >= max(MIN_PROBE_CHARACTERS, baseline * PROBE_WIN_MARGIN)) {
                val attempt = readPage(bitmap, best)
                if (charactersIn(attempt) > charactersIn(lines)) {
                    logcat {
                        "Page reads as '$best' ($bestCount chars on the probe band) rather than the " +
                            "configured '$sourceLanguage' ($baseline)"
                    }
                    lines = attempt
                    language = best
                }
            }
        }

        val groups = groupIntoParagraphs(lines)
        val boxes = ArrayList<BubbleBox>(groups.size)
        val texts = ArrayList<BubbleText>(groups.size)
        for (group in groups) {
            val characters = group.sumOf { line -> line.text.count { it.isLetterOrDigit() } }
            val text = group.joinToString("\n") { it.text.trim() }.trim()
            // A stray glyph or two recognised off artwork is noise, and turning it into a region means
            // erasing a piece of the drawing to letter nonsense over it. Real dialogue clears one of
            // three bars: enough letters to be a sentence, more than one line, or the shape of a
            // spoken interjection — which is how "NO!" survives without "Lk" surviving with it.
            val meaningful = characters >= MIN_STANDALONE_CHARACTERS ||
                (group.size > 1 && characters >= MIN_MULTILINE_CHARACTERS) ||
                ShortDialogueNormalizer.isLikelyUtterance(text)
            if (!meaningful) {
                logcat { "Dropping $text as noise rather than dialogue" }
                continue
            }
            val bounds = Rect(group[0].rect)
            group.drop(1).forEach { bounds.union(it.rect) }
            val padX = (bounds.width() * BLOCK_PAD_RATIO).roundToInt().coerceIn(4, 32)
            val padY = (bounds.height() * BLOCK_PAD_RATIO).roundToInt().coerceIn(4, 32)
            boxes += BubbleBox(
                left = (bounds.left - padX).coerceAtLeast(0),
                top = (bounds.top - padY).coerceAtLeast(0),
                right = (bounds.right + padX).coerceAtMost(bitmap.width),
                bottom = (bounds.bottom + padY).coerceAtMost(bitmap.height),
                confidence = READ_CONFIDENCE,
                isTextBlock = true,
            )
            texts += BubbleText(text = text, lines = group)
        }

        logcat { "Read ${boxes.size} lettering region(s) from ${lines.size} line(s) as '$language'" }
        boxes.indices.forEach { index ->
            logcat { "  region ${boxes[index].toRect()}: ${texts[index].text.lines().joinToString(" / ")}" }
        }
        return Result(boxes, texts, language)
    }

    /**
     * Reads every line on the page.
     *
     * ML Kit's input cap is well below a webtoon strip's height, so the page is read in bands. The
     * overlap exists so a paragraph split across a boundary is still whole in one band; lines found
     * twice are reconciled afterwards by geometry, which is exact and much cheaper than reading with
     * a larger overlap would be.
     */
    private fun readPage(bitmap: Bitmap, sourceLanguage: String): List<ReadLine> =
        withRecognizer(sourceLanguage) { recognizer ->
            val found = ArrayList<ReadLine>()
            var top = 0
            while (top < bitmap.height) {
                val height = min(BAND_HEIGHT, bitmap.height - top)
                if (height < MIN_BAND_HEIGHT) break
                val band = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                try {
                    found += readBand(recognizer, band, top, padEdges = bitmap.height > BAND_HEIGHT)
                } finally {
                    // createBitmap hands back the *source* when the region is the whole image, and
                    // recycling that would destroy the page mid-translation.
                    if (band !== bitmap) band.recycle()
                }
                if (top + height >= bitmap.height) break
                top += BAND_HEIGHT - BAND_OVERLAP
            }
            dedupe(found)
        }

    private fun readBand(
        recognizer: TextRecognizer,
        band: Bitmap,
        offsetY: Int,
        padEdges: Boolean,
    ): List<ReadLine> {
        val edgePad = if (padEdges) BAND_EDGE_CONTEXT else 0
        val input = if (edgePad == 0) {
            band
        } else {
            Bitmap.createBitmap(band.width, band.height + edgePad * 2, Bitmap.Config.ARGB_8888).also { padded ->
                Canvas(padded).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(band, 0f, edgePad.toFloat(), null)
                }
            }
        }

        val latch = CountDownLatch(1)
        val lines = ArrayList<ReadLine>()
        recognizer.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { visionText ->
                visionText.textBlocks.forEachIndexed { blockIndex, block ->
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        // `line.text` occasionally glues visually separated words together on
                        // outlined lettering ("Intheyear"); the elements retain the word boundaries,
                        // which materially improves both translation and wrapping.
                        val text = line.elements.joinToString(" ") { it.text.trim() }.ifBlank { line.text }
                        if (text.isBlank()) continue
                        lines += ReadLine(
                            line = TextLineBox(Rect(rect).apply { offset(0, offsetY - edgePad) }, text),
                            paragraph = offsetY.toString() + "#" + blockIndex,
                            bandTop = offsetY,
                            bandBottom = offsetY + band.height,
                        )
                    }
                }
                latch.countDown()
            }
            .addOnFailureListener {
                logcat { "Page read failed on band at $offsetY: ${it.message}" }
                latch.countDown()
            }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logcat { "Page read timed out on band at $offsetY" }
        }
        if (input !== band) input.recycle()
        return lines
    }

    /**
     * Drops the second sighting of a line that fell inside two overlapping bands.
     *
     * Position alone decides it. Requiring the two readings to *agree* looks safer and is the exact
     * opposite: the copy read at a band edge is the one that comes back wrong, so the readings that
     * most need reconciling are precisely the ones that do not match. Left in, both were kept, and a
     * balloon went to the translator as "YOU MUST NEVER / YUu MUラI NじVEK" — the same line twice, once
     * as noise — which was then lettered into the page as a paragraph a line longer than the original.
     *
     * Two genuinely different lines cannot collide here: consecutive lines of text barely overlap
     * vertically at all, nowhere near [DUPLICATE_OVERLAP] of the smaller box.
     */
    private fun dedupe(lines: List<ReadLine>): List<ReadLine> {
        if (lines.size < 2) return lines
        val ordered = lines.sortedWith(compareBy({ it.line.rect.top }, { it.line.rect.left }))
        val kept = ArrayList<ReadLine>(ordered.size)
        for (candidate in ordered) {
            val duplicate = kept.indexOfFirst { other ->
                overlapFraction(candidate.line.rect, other.line.rect) >= DUPLICATE_OVERLAP
            }
            if (duplicate < 0) {
                kept += candidate
                continue
            }
            val incumbent = kept[duplicate]
            val better = candidate.margin > incumbent.margin ||
                (candidate.margin == incumbent.margin && candidate.line.text.length > incumbent.line.text.length)
            if (better) kept[duplicate] = candidate
        }
        return kept
    }

    /**
     * Joins lines that belong to one balloon or panel.
     *
     * ML Kit has already grouped the lines it read into paragraphs, and its grouping is better than
     * anything derived from rectangles afterwards, so those stay together by construction. What is
     * left to do is join paragraphs: one balloon is regularly reported as two or three blocks, and
     * translating each separately cuts a sentence into fragments that are then lettered at their own
     * sizes — on the page that exposed this, one balloon came out as three, the middle third of it
     * left in English underneath a smaller Vietnamese remainder.
     *
     * Vertically adjacent and horizontally overlapping is the test, which is also how a status panel
     * — something the speech-bubble model never saw at all — comes back as one region.
     */
    private fun groupIntoParagraphs(lines: List<ReadLine>): List<List<TextLineBox>> {
        if (lines.isEmpty()) return emptyList()
        val remaining = lines.groupBy { it.paragraph }.values
            .map { paragraph -> paragraph.map { it.line }.sortedWith(compareBy({ it.rect.top }, { it.rect.left })) }
            .sortedWith(compareBy({ it[0].rect.top }, { it[0].rect.left }))
            .toMutableList()
        val groups = ArrayList<List<TextLineBox>>()

        while (remaining.isNotEmpty()) {
            val members = ArrayList(remaining.removeAt(0))
            val bounds = Rect(members[0].rect)
            members.drop(1).forEach { bounds.union(it.rect) }
            var grew = true
            while (grew) {
                grew = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    val candidateBounds = Rect(candidate[0].rect)
                    candidate.drop(1).forEach { candidateBounds.union(it.rect) }
                    val gap = candidateBounds.top - bounds.bottom
                    val lineHeight = typeSize(members)
                    val horizontal = min(bounds.right, candidateBounds.right) -
                        max(bounds.left, candidateBounds.left)
                    val narrower = min(bounds.width(), candidateBounds.width())
                    val closeEnough = gap <= lineHeight * MERGE_GAP_RATIO && gap >= -bounds.height()
                    // Two paragraphs set at very different sizes are two different things, however
                    // close together they sit. A chapter title and the credit line under it are the
                    // case that matters: merged, they become one long paragraph, which is enough
                    // words to escape the guard that leaves decorative artwork alone — so the title
                    // art was erased and re-lettered as a run-on sentence across the illustration.
                    val candidateSize = typeSize(candidate)
                    val sameType = max(lineHeight, candidateSize) <=
                        min(lineHeight, candidateSize) * MERGE_SIZE_RATIO
                    if (closeEnough && sameType && horizontal > narrower * MERGE_OVERLAP_RATIO) {
                        members += candidate
                        bounds.union(candidateBounds)
                        iterator.remove()
                        grew = true
                    }
                }
            }
            groups += members.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        }
        // Reading order: down the page, left to right within a row of comparable height.
        val band = max(MIN_READING_BAND, groups.sumOf { it[0].rect.height() } / groups.size)
        return groups.sortedWith(compareBy({ it[0].rect.top / band }, { it[0].rect.left }))
    }

    private fun charactersIn(lines: List<ReadLine>): Int =
        lines.sumOf { read -> read.line.text.count { it.isLetterOrDigit() } }

    /** How big the type is in a paragraph: the median line thickness, not the tallest line. */
    private fun typeSize(lines: List<TextLineBox>): Int {
        val heights = lines.map { min(it.rect.height(), it.rect.width()) }.sorted()
        return max(1, heights[heights.size / 2])
    }

    /** One band worth of page, taken from its middle, for deciding which script it is written in. */
    private fun probeBand(bitmap: Bitmap): Bitmap {
        if (bitmap.height <= BAND_HEIGHT) return bitmap
        val top = ((bitmap.height - BAND_HEIGHT) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, BAND_HEIGHT)
    }

    private fun overlapFraction(a: Rect, b: Rect): Float {
        val overlap = Rect(a)
        if (!overlap.intersect(b)) return 0f
        val smaller = min(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        if (smaller <= 0) return 0f
        return (overlap.width().toLong() * overlap.height()).toFloat() / smaller
    }

    /** One recognised line, tagged with the ML Kit block and the band it was read in. */
    private data class ReadLine(
        val line: TextLineBox,
        val paragraph: String,
        val bandTop: Int,
        val bandBottom: Int,
    ) {
        /**
         * How much page the recogniser had around this line, in pixels of the nearer band edge.
         *
         * A line close to a band boundary is read with half its context missing and comes back
         * mangled — "YOU MUST NEVER" as "YUu MUラI NじVEK" — so when two bands both saw a line, this
         * is what decides which reading to believe.
         */
        val margin: Int
            get() {
                val centre = (line.rect.top + line.rect.bottom) / 2
                return min(centre - bandTop, bandBottom - centre)
            }
    }

    private inline fun <T> withRecognizer(sourceLanguage: String, block: (TextRecognizer) -> T): T {
        val recognizer = when (sourceLanguage) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
        }
        return try {
            block(recognizer)
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        /** Scripts to try when the configured one reads nothing off a page. */
        val SCRIPT_FALLBACK_ORDER = listOf("ja", "zh", "ko", "en")
        /** Characters off the probe band that settle the question without trying the rest. */
        const val PROBE_CONFIDENT_CHARACTERS = 40
        /** Below this, per band, the configured recogniser has not really read the page. */
        const val MIN_CHARACTERS_PER_BAND = 12
        /** A challenger must read at least this much, and this many times the configured script. */
        const val MIN_PROBE_CHARACTERS = 20
        const val PROBE_WIN_MARGIN = 2

        /** Band height for reading a long strip. Comfortably inside ML Kit's input limits. */
        const val BAND_HEIGHT = 1200

        /**
         * Overlap between bands.
         *
         * A quarter of the band is enough here, where the old text-block pass needed half: this reads
         * *lines*, and a line taller than 300px does not occur, so every line is whole in some band.
         * The old pass had to keep whole multi-paragraph panels intact, and paid for it with twice as
         * many ML Kit calls over the same strip.
         */
        const val BAND_OVERLAP = 300
        const val BAND_EDGE_CONTEXT = 96
        const val MIN_BAND_HEIGHT = 80

        /** Overlap of the smaller rectangle above which two sightings are the same line. */
        const val DUPLICATE_OVERLAP = 0.55f

        /** Vertical gap, as a multiple of line height, still counted as the same paragraph. */
        const val MERGE_GAP_RATIO = 0.9f

        /** Horizontal overlap, as a fraction of the narrower box, required to join a paragraph. */
        const val MERGE_OVERLAP_RATIO = 0.4f

        /** How far two paragraphs' type sizes may differ and still be one paragraph. */
        const val MERGE_SIZE_RATIO = 1.8f

        /** Letters or digits a lone line needs before it counts as dialogue rather than noise. */
        const val MIN_STANDALONE_CHARACTERS = 3
        const val MIN_MULTILINE_CHARACTERS = 2

        const val BLOCK_PAD_RATIO = 0.04f
        const val MIN_READING_BAND = 24

        /**
         * Every region here comes from reading the page rather than from the bubble model, so they
         * all carry the synthetic confidence the rest of the pipeline expects for those.
         */
        const val READ_CONFIDENCE = 0.15f
    }
}
