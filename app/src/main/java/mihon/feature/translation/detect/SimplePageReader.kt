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
import mihon.feature.translation.FuriganaGuard
import mihon.feature.translation.JapaneseOcrCleaner
import mihon.feature.translation.JapaneseSfxGuard
import mihon.feature.translation.PageFormat
import mihon.feature.translation.PageFormatDetector
import mihon.feature.translation.ScriptKindDetector
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
     * Script the previous page probed as, tried first on the next one.
     *
     * Only ever an ordering hint: every candidate is still scored the same way, so a chapter that
     * genuinely changes script is picked up on the page it changes.
     */
    private var lastProbeWinner: String? = null

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
        // "Which language is this" is a question about the page, so answer it from the page. Reading
        // the whole thing with a guessed recogniser first and correcting afterwards costs a full
        // page pass, and the guess used to come from a single app-wide setting — which is how a
        // Korean series carried on being read as Japanese after the reader left a Japanese one.
        val configured = if (sourceLanguage.isBlank() || sourceLanguage == AUTO) {
            probeLanguage(bitmap)
        } else {
            sourceLanguage
        }
        var lines = readPage(bitmap, configured)
        var language = configured

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
            val baseline = withRecognizer(configured) {
                charactersIn(readBand(it, probe, 0, padEdges = false))
            }
            var best: String? = null
            var bestCount = 0
            for (candidate in SCRIPT_FALLBACK_ORDER) {
                if (candidate == configured) continue
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
                            "first-pass '$configured' ($baseline)"
                    }
                    lines = attempt
                    language = best
                }
            }
        }

        val format = PageFormatDetector.detect(bitmap.width, bitmap.height, ScriptKindDetector.ofLanguage(language))
        val mergedLines = mergeCompanionScripts(bitmap, lines, language, format)
        val groups = dropFurigana(groupIntoParagraphs(mergedLines, bitmap))
        val boxes = ArrayList<BubbleBox>(groups.size)
        val texts = ArrayList<BubbleText>(groups.size)
        for (group in groups) {
            val characters = group.sumOf { line -> line.text.count { it.isLetterOrDigit() } }
            val cjkLetters = group.sumOf { line -> line.text.count(::isCjkLetter) }
            val text = JapaneseOcrCleaner.clean(group.joinToString("\n") { it.text.trim() }.trim())
            // A stray glyph or two recognised off artwork is noise, and turning it into a region means
            // erasing a piece of the drawing to letter nonsense over it. Real dialogue clears one of
            // three bars: enough letters to be a sentence, more than one line, or the shape of a
            // spoken interjection — which is how "NO!" survives without "Lk" surviving with it.
            //
            // CJK is different: two kanji is a word (何故, 本当, 私だ). The Latin floor of three
            // letters would drop the first column of a balloon after furigana was peeled off.
            val meaningful = characters >= MIN_STANDALONE_CHARACTERS ||
                cjkLetters >= MIN_CJK_STANDALONE ||
                (group.size > 1 && characters >= MIN_MULTILINE_CHARACTERS) ||
                ShortDialogueNormalizer.isLikelyUtterance(text)
            if (!meaningful) {
                logcat { "Dropping $text as noise rather than dialogue" }
                continue
            }
            if (JapaneseSfxGuard.shouldDrop(text)) {
                logcat { "Dropping $text as Japanese sound-effect lettering" }
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
    private fun groupIntoParagraphs(lines: List<ReadLine>, bitmap: Bitmap): List<List<TextLineBox>> {
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
                    val lineHeight = typeSize(members)
                    // Manga is set in columns, so the next line of a sentence is *beside* this one,
                    // not under it. Left as a purely vertical test, every column of a balloon became
                    // its own region — and a region one column wide is a tall sliver, into which the
                    // horizontal replacement had to be set at about six pixels to fit. Swapping the
                    // axes for vertical lettering makes a balloon one region again, the same shape
                    // the translation needs.
                    val vertical = isVertical(members) && isVertical(candidate)
                    val gap = if (vertical) {
                        max(bounds.left - candidateBounds.right, candidateBounds.left - bounds.right)
                    } else {
                        candidateBounds.top - bounds.bottom
                    }
                    val across = if (vertical) {
                        min(bounds.bottom, candidateBounds.bottom) - max(bounds.top, candidateBounds.top)
                    } else {
                        min(bounds.right, candidateBounds.right) - max(bounds.left, candidateBounds.left)
                    }
                    val narrower = if (vertical) {
                        min(bounds.height(), candidateBounds.height())
                    } else {
                        min(bounds.width(), candidateBounds.width())
                    }
                    val span = if (vertical) bounds.width() else bounds.height()
                    val closeEnough = gap <= lineHeight * MERGE_GAP_RATIO && gap >= -span
                    // Two paragraphs set at very different sizes are two different things, however
                    // close together they sit. A chapter title and the credit line under it are the
                    // case that matters: merged, they become one long paragraph, which is enough
                    // words to escape the guard that leaves decorative artwork alone — so the title
                    // art was erased and re-lettered as a run-on sentence across the illustration.
                    val candidateSize = typeSize(candidate)
                    val sameType = max(lineHeight, candidateSize) <=
                        min(lineHeight, candidateSize) * MERGE_SIZE_RATIO
                    // Nor are two paragraphs printed on different backgrounds one paragraph. A line
                    // of handwriting on the night sky beside a balloon is adjacent to the balloon's
                    // lettering and the same size as it, and belongs to neither it nor its balloon:
                    // merged, the translation ran out past the balloon's edge, and — because the
                    // paper colour was then measured across both — the erase painted the balloon's
                    // white over the sky, one rectangle per line of it.
                    val samePaper =
                        channelDistance(paperOf(members, bitmap), paperOf(candidate, bitmap)) <= MERGE_PAPER_DISTANCE
                    val sameScript = ScriptKindDetector.sameWritingSystem(
                        ScriptKindDetector.of(members.joinToString("") { it.text }),
                        ScriptKindDetector.of(candidate.joinToString("") { it.text }),
                    )
                    val overlaps = across > narrower * MERGE_OVERLAP_RATIO
                    // Narrate only near misses. A page has hundreds of obviously unrelated pairs and
                    // none of them explains anything; the pairs worth seeing are the ones that were
                    // adjacent and overlapping and still did not join.
                    if (closeEnough && overlaps && !(sameType && samePaper)) {
                        logcat {
                            "Not one paragraph: $bounds + $candidateBounds — " +
                                "type $lineHeight vs $candidateSize, same paper: $samePaper"
                        }
                    }
                    if (closeEnough && sameType && samePaper && sameScript && overlaps) {
                        members += candidate
                        bounds.union(candidateBounds)
                        iterator.remove()
                        grew = true
                    }
                }
            }
            // Columns of vertical lettering are read right to left, so the rightmost is the start
            // of the sentence. Ordering them left to right hands the translator its clauses
            // backwards — the greeting on the test page came back as "ございます / おはよう".
            groups += if (isVertical(members)) {
                members.sortedWith(compareByDescending<TextLineBox> { it.rect.left }.thenBy { it.rect.top })
            } else {
                members.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            }
        }
        // Reading order: down the page. Japanese manga is right-to-left within a row of panels;
        // everything else is left-to-right. The language is not known here — the caller reorders
        // after the page script is settled — so this stays left-to-right and [inJapaneseOrder]
        // is applied once we know.
        val band = max(MIN_READING_BAND, groups.sumOf { it[0].rect.height() } / groups.size)
        return groups.sortedWith(compareBy({ it[0].rect.top / band }, { it[0].rect.left }))
    }

    /**
     * A mixed-language strip (Korean + Spanish is the case that showed this) is read first in the
     * winning script. The other script's balloons then arrive as two junk glyphs and are discarded
     * as noise. Reading the companions and keeping any line that is clearly the other script is
     * how those balloons survive.
     */
    private fun mergeCompanionScripts(
        bitmap: Bitmap,
        primary: List<ReadLine>,
        language: String,
        format: PageFormat,
    ): List<ReadLine> {
        val companions = PageFormatDetector.companionScripts(format, language)
        if (companions.isEmpty()) return primary
        // Always try the other script on a webtoon: a Korean page with three Spanish balloons
        // already has enough Hangul to skip the page-level fallback.
        if (format != PageFormat.WEBTOON && !primaryLikelyMixed(primary)) return primary
        val merged = primary.toMutableList()
        var added = 0
        var replaced = 0
        for (companion in companions.take(MAX_COMPANION_SCRIPTS)) {
            val expected = ScriptKindDetector.ofLanguage(companion)
            val alt = readPage(bitmap, companion)
            for (line in alt) {
                val kind = ScriptKindDetector.of(line.line.text)
                if (kind != expected) continue
                if (line.line.text.count { it.isLetter() } < MIN_COMPANION_LETTERS) continue
                val overlap = merged.indexOfFirst { other ->
                    overlapFraction(other.line.rect, line.line.rect) >= DUPLICATE_OVERLAP
                }
                if (overlap < 0) {
                    merged += line
                    added++
                    continue
                }
                val incumbent = merged[overlap]
                val incumbentKind = ScriptKindDetector.of(incumbent.line.text)
                // The primary recogniser often reports two junk glyphs on a balloon written in
                // the other script. Those occupy the same rectangle; keep the real reading.
                if (incumbentKind != expected &&
                    (incumbentKind != ScriptKindDetector.ofLanguage(language) ||
                        ScriptKindDetector.looksLikeJunk(incumbent.line.text, expected))
                ) {
                    merged[overlap] = line
                    replaced++
                }
            }
        }
        if (added == 0 && replaced == 0) return primary
        logcat { "Companion '$language' pass: +$added line(s), replaced $replaced junk reading(s)" }
        return merged
    }

    private fun primaryLikelyMixed(lines: List<ReadLine>): Boolean {
        var latin = 0
        var cjk = 0
        for (line in lines) {
            for (ch in line.line.text) {
                if (!ch.isLetter()) continue
                if (ch.code < 0x0250) latin++ else cjk++
            }
        }
        return latin >= MIN_COMPANION_LETTERS && cjk >= MIN_COMPANION_LETTERS
    }

    /**
     * Drops ruby lines that sit beside a larger Japanese host so they are not lettered as dialogue.
     *
     * Furigana is reported as its own paragraph because it is set at half the type size; leaving it
     * in produces a second translation next to the sentence it annotates.
     */
    private fun dropFurigana(groups: List<List<TextLineBox>>): List<List<TextLineBox>> {
        if (groups.size < 2) return groups
        val described = groups.map { group ->
            val bounds = Rect(group[0].rect)
            group.drop(1).forEach { bounds.union(it.rect) }
            FuriganaGuard.Line(
                text = group.joinToString("") { it.text },
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                stroke = typeSize(group),
            )
        }
        val dropped = FuriganaGuard.dropIndices(described)
        if (dropped.isEmpty()) return groups
        dropped.forEach { index ->
            logcat { "Dropping furigana ${described[index].text} at ${groups[index][0].rect}" }
        }
        return groups.filterIndexed { index, _ -> index !in dropped }
    }

    private fun charactersIn(lines: List<ReadLine>): Int =
        lines.sumOf { read -> read.line.text.count { it.isLetterOrDigit() } }

    /**
     * The colour a paragraph is printed on.
     *
     * The median of its line boxes, for the same reason the renderer uses it: lettering never fills
     * half of its own bounding box, so the middle of that distribution is the paper it sits on.
     * Sampled sparsely — this decides a merge, not a pixel.
     */
    private fun paperOf(lines: List<TextLineBox>, bitmap: Bitmap): Int {
        val histogram = IntArray(256)
        val samples = ArrayList<Int>()
        for (line in lines) {
            // Widen the box first. A recognised line box hugs its glyphs, and bold lettering fills
            // more than half of that — so the median of the box as reported is the *ink*, and two
            // lines of one balloon came back with different "paper" and refused to merge.
            val margin = max(3, (min(line.rect.height(), line.rect.width()) * PAPER_MARGIN).roundToInt())
            val left = (line.rect.left - margin).coerceIn(0, bitmap.width - 1)
            val top = (line.rect.top - margin).coerceIn(0, bitmap.height - 1)
            val right = (line.rect.right + margin).coerceIn(left + 1, bitmap.width)
            val bottom = (line.rect.bottom + margin).coerceIn(top + 1, bitmap.height)
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val pixel = bitmap.getPixel(x, y)
                    samples += pixel
                    histogram[luminanceOf(pixel)]++
                    x += PAPER_SAMPLE_STEP
                }
                y += PAPER_SAMPLE_STEP
            }
        }
        if (samples.isEmpty()) return Color.WHITE
        var seen = 0
        var median = 0
        val half = samples.size / 2
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen > half) {
                median = value
                break
            }
        }
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (pixel in samples) {
            if (kotlin.math.abs(luminanceOf(pixel) - median) > PAPER_BAND) continue
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            count++
        }
        if (count == 0) return samples[samples.size / 2]
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun luminanceOf(color: Int): Int =
        (299 * ((color shr 16) and 0xFF) + 587 * ((color shr 8) and 0xFF) + 114 * (color and 0xFF)) / 1000

    /** Max per-channel difference, in 0..255. */
    private fun channelDistance(color: Int, other: Int): Int = max(
        kotlin.math.abs(((color shr 16) and 0xFF) - ((other shr 16) and 0xFF)),
        max(
            kotlin.math.abs(((color shr 8) and 0xFF) - ((other shr 8) and 0xFF)),
            kotlin.math.abs((color and 0xFF) - (other and 0xFF)),
        ),
    )

    private fun isCjkLetter(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x3040..0x30FF || cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF ||
            cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF
    }

    /** True when this paragraph is set in columns rather than rows — manga, and vertical signage. */
    private fun isVertical(lines: List<TextLineBox>): Boolean =
        lines.count { it.rect.height() > it.rect.width() * VERTICAL_ASPECT } * 2 > lines.size

    /** How big the type is in a paragraph: the median line thickness, not the tallest line. */
    private fun typeSize(lines: List<TextLineBox>): Int {
        val heights = lines.map { min(it.rect.height(), it.rect.width()) }.sorted()
        return max(1, heights[heights.size / 2])
    }

    /** One band worth of page, taken from its middle, for deciding which script it is written in. */
    /**
     * Picks the recogniser for a page whose language nobody has declared.
     *
     * One band from the middle of the page is read with each CJK recogniser and scored on how much
     * of *its own script* it found — not on how many characters it returned. Raw character count is
     * the obvious metric and it is wrong: every CJK recogniser also reads Latin, so an English
     * scanlation comes back with a healthy character count from the Japanese model and the page is
     * then read, ordered and typeset as Japanese. Scoring on kana/hangul/han means a Latin page
     * scores zero everywhere and falls to the Latin recogniser, which is the right answer.
     *
     * A band is enough because a page is written in one script, and it is cheap enough — a fraction
     * of a page — to run before the real pass rather than after a wasted one.
     */
    private fun probeLanguage(bitmap: Bitmap): String {
        val probe = probeBand(bitmap)
        try {
            var best = "en"
            var bestScore = 0
            // Whatever the last page turned out to be, first. A chapter is written in one script, so
            // after the first page the early exit below ends the probe on its first read instead of
            // loading and running all three recognisers — measured at about four seconds a page on a
            // 2069x2880 raw, which is most of what the probe costs.
            val order = lastProbeWinner
                ?.let { listOf(it) + CJK_PROBE_ORDER.filterNot { candidate -> candidate == it } }
                ?: CJK_PROBE_ORDER
            for (candidate in order) {
                val score = withRecognizer(candidate) {
                    scriptCharactersIn(readBand(it, probe, 0, padEdges = false), candidate)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
                if (score >= PROBE_CONFIDENT_CHARACTERS) break
            }
            if (bestScore < MIN_PROBE_CHARACTERS) {
                logcat { "Probe found no CJK worth trusting (best $best scored $bestScore); reading as Latin" }
                lastProbeWinner = null
                return "en"
            }
            logcat { "Page reads best as '$best' ($bestScore ${best}-script chars on the probe band)" }
            lastProbeWinner = best
            return best
        } finally {
            if (probe !== bitmap) probe.recycle()
        }
    }

    /** Characters belonging to [language]'s own script, ignoring anything every recogniser reads. */
    private fun scriptCharactersIn(lines: List<ReadLine>, language: String): Int =
        lines.sumOf { read ->
            read.line.text.count { ch ->
                val cp = ch.code
                when (language) {
                    "ja" -> cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF ||
                        cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF
                    "zh" -> cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF
                    "ko" -> cp in 0x1100..0x11FF || cp in 0xAC00..0xD7AF
                    else -> ch.isLetterOrDigit()
                }
            }
        }

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

        /** Source-language value meaning "work it out from the page". */
        const val AUTO = "auto"

        /** Scripts to try when the configured one reads nothing off a page. */
        val SCRIPT_FALLBACK_ORDER = listOf("ja", "zh", "ko", "en")

        /**
         * Recognisers the up-front probe tries. Latin is absent on purpose: it is the fallback when
         * no CJK script scores, so probing for it would only be a way to beat a real CJK reading.
         */
        val CJK_PROBE_ORDER = listOf("ja", "ko", "zh")
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

        /**
         * Gap between paragraphs, as a multiple of type size, still counted as one paragraph.
         *
         * Measured against the *recognised box*, which hugs the glyphs and so is a good deal shorter
         * than the line's leading. At 0.9 an ordinary balloon whose lines sat 56px apart on 27px
         * boxes — a gap of 29 against a threshold of 24 — was split into one region per line, and
         * each line was then translated and lettered on its own.
         */
        const val MERGE_GAP_RATIO = 1.4f

        /** Horizontal overlap, as a fraction of the narrower box, required to join a paragraph. */
        const val MERGE_OVERLAP_RATIO = 0.4f

        /** How far two paragraphs' type sizes may differ and still be one paragraph. */
        const val MERGE_SIZE_RATIO = 1.8f

        /** Height-to-width ratio above which a recognised line is a column, not a row. */
        const val VERTICAL_ASPECT = 1.5f

        /** How far two paragraphs' backgrounds may differ and still be one paragraph. */
        const val MERGE_PAPER_DISTANCE = 55
        const val PAPER_SAMPLE_STEP = 3
        const val PAPER_BAND = 12
        /** Outset of a line box before sampling, as a fraction of its short side. */
        const val PAPER_MARGIN = 0.55f

        /** Letters or digits a lone line needs before it counts as dialogue rather than noise. */
        const val MIN_STANDALONE_CHARACTERS = 3
        /** Two CJK letters is a word; the Latin floor of three would drop 何故 after its ruby. */
        const val MIN_CJK_STANDALONE = 2
        const val MIN_MULTILINE_CHARACTERS = 2
        const val MAX_COMPANION_SCRIPTS = 2
        const val MIN_COMPANION_LETTERS = 3

        const val BLOCK_PAD_RATIO = 0.04f
        const val MIN_READING_BAND = 24

        /**
         * Every region here comes from reading the page rather than from the bubble model, so they
         * all carry the synthetic confidence the rest of the pipeline expects for those.
         */
        const val READ_CONFIDENCE = 0.15f
    }
}
