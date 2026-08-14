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
import mihon.feature.translation.model.BubbleBox
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Finds blocks of lettering the bubble detector misses.
 *
 * The ONNX model was trained on speech bubbles, so it sees ellipses and rounded rectangles with
 * tails. It does not see the thing these series are full of: the game-style status window — a
 * translucent panel of skill descriptions and stat lines, no outline the model recognises, often
 * bleeding off the page edge. Those panels came back untranslated on every page, and no amount of
 * work downstream helps, because nothing downstream is ever told they exist.
 *
 * ML Kit already reads the whole page for us elsewhere, and a text block it reports is by definition
 * lettering. Anything it finds that no bubble covers is a candidate. The result is merged into the
 * detector's output as a low-confidence box.
 *
 * Blocking; call from a background dispatcher.
 */
class TextBlockDetector {

    /**
     * @param existing boxes already found by the bubble detector, in source-image pixels
     * @return additional boxes for lettering outside [existing], in the same coordinate space
     */
    fun detect(bitmap: Bitmap, existing: List<BubbleBox>, sourceLanguage: String): List<BubbleBox> {
        // ML Kit's input cap is well below a webtoon strip's height, so the page is read in bands.
        // Bands overlap so a panel split across a boundary is still found whole in one of them.
        val recognizer = recognizerFor(sourceLanguage)
        val found = ArrayList<TextCandidate>()
        try {
            var top = 0
            while (top < bitmap.height) {
                val height = minOf(BAND_HEIGHT, bitmap.height - top)
                if (height < MIN_BAND_HEIGHT) break
                val band = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                try {
                    found += readBlocks(
                        recognizer = recognizer,
                        band = band,
                        offsetY = top,
                        padEdges = bitmap.height > BAND_HEIGHT,
                    )
                } finally {
                    // createBitmap hands back the *source* when the requested region is the whole
                    // image. Recycling that destroys the page mid-translation, and every later stage
                    // fails with "cannot use a recycled source" — which is what happened to short
                    // pages, whose single band covers everything.
                    if (band !== bitmap) band.recycle()
                }
                if (top + height >= bitmap.height) break
                top += BAND_HEIGHT - BAND_OVERLAP
            }
        } finally {
            runCatching { recognizer.close() }
        }

        val merged = mergeNearby(found)
        val extras = merged
            .filter { it.rect.width() >= MIN_BLOCK_SIDE && it.rect.height() >= MIN_BLOCK_SIDE }
            .mapNotNull { candidate ->
                val rect = candidate.rect
                val coveringSpeech = existing.firstOrNull { box -> overlaps(rect, box) }
                if (!TextBlockFallbackPolicy.shouldKeep(candidate.characters, coveringSpeech != null, candidate.text)) {
                    return@mapNotNull null
                }
                val fallbackOnly = coveringSpeech != null &&
                    !OversizedSpeechRefiner.shouldReplace(
                        coveringSpeech.toRefinerBounds(),
                        rect.toRefinerBounds(),
                    )
                // Padded outward: ML Kit's block box hugs the glyphs, and the renderer needs the
                // panel around them to sample a fill colour and to place the replacement text.
                val padX = (rect.width() * BLOCK_PAD_RATIO).roundToInt().coerceIn(6, 40)
                val padY = (rect.height() * BLOCK_PAD_RATIO).roundToInt().coerceIn(6, 40)
                BubbleBox(
                    left = (rect.left - padX).coerceAtLeast(0),
                    top = (rect.top - padY).coerceAtLeast(0),
                    right = (rect.right + padX).coerceAtMost(bitmap.width),
                    bottom = (rect.bottom + padY).coerceAtMost(bitmap.height),
                    confidence = SYNTHETIC_CONFIDENCE,
                    isTextBlock = true,
                    isFallbackTextBlock = fallbackOnly,
                )
            }

        if (extras.isNotEmpty()) {
            logcat {
                "Text-block pass added ${extras.count { !it.isFallbackTextBlock }} region(s) and kept " +
                    "${extras.count { it.isFallbackTextBlock }} speech fallback(s)"
            }
        }
        return extras
    }

    private fun readBlocks(
        recognizer: TextRecognizer,
        band: Bitmap,
        offsetY: Int,
        padEdges: Boolean,
    ): List<TextCandidate> {
        val latch = CountDownLatch(1)
        val blocks = ArrayList<TextCandidate>()
        val edgePad = if (padEdges) BAND_EDGE_CONTEXT else 0
        val input = if (edgePad == 0) {
            band
        } else {
            Bitmap.createBitmap(
                band.width,
                band.height + edgePad * 2,
                Bitmap.Config.ARGB_8888,
            ).also { padded ->
                Canvas(padded).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(band, 0f, edgePad.toFloat(), null)
                }
            }
        }

        recognizer.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    // Require real words: a single stray glyph recognised off artwork is noise, and
                    // turning it into a box means erasing a piece of the drawing for nothing.
                    val characters = block.text.count { it.isLetterOrDigit() }
                    // Keep short fragments provisionally. Manga typesetting often puts a short word
                    // ("AND", "NO", "SO") in its own ML Kit block above a longer paragraph. It is
                    // accepted only if mergeNearby attaches it to enough real lettering; isolated
                    // artwork noise still fails MIN_BLOCK_CHARS after merging.
                    if (!TextBlockFallbackPolicy.shouldReadFragment(characters, block.text)) continue
                    val box = block.boundingBox ?: continue
                    blocks += TextCandidate(
                        Rect(box).apply { offset(0, offsetY - edgePad) },
                        characters,
                        block.text,
                    )
                }
                latch.countDown()
            }
            .addOnFailureListener {
                logcat { "Text-block pass failed on band at $offsetY: ${it.message}" }
                latch.countDown()
            }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logcat { "Text-block pass timed out on band at $offsetY" }
        }
        if (input !== band) input.recycle()
        return blocks
    }

    /**
     * Joins blocks that belong to one panel.
     *
     * ML Kit splits a status window into a block per paragraph, and translating those separately
     * loses the fact that they are one panel — each would be erased and re-lettered on its own,
     * at its own font size. Blocks close together vertically and overlapping horizontally are one.
     */
    private fun mergeNearby(blocks: List<TextCandidate>): List<TextCandidate> {
        if (blocks.size < 2) return blocks
        val remaining = blocks.sortedWith(compareBy({ it.rect.top }, { it.rect.left })).toMutableList()
        val merged = ArrayList<TextCandidate>()

        while (remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            val current = Rect(first.rect)
            var characters = first.characters
            val text = StringBuilder(first.text)
            var grew = true
            while (grew) {
                grew = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    val gap = other.rect.top - current.bottom
                    val horizontal = kotlin.math.min(current.right, other.rect.right) -
                        kotlin.math.max(current.left, other.rect.left)
                    val narrower = kotlin.math.min(current.width(), other.rect.width())
                    val closeEnough = gap <= current.height() * MERGE_GAP_RATIO &&
                        gap >= -current.height()
                    if (closeEnough && horizontal > narrower * MERGE_OVERLAP_RATIO) {
                        current.union(other.rect)
                        characters += other.characters
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(other.text)
                        iterator.remove()
                        grew = true
                    }
                }
            }
            merged += TextCandidate(current, characters, text.toString())
        }
        return merged
    }

    private data class TextCandidate(val rect: Rect, val characters: Int, val text: String)

    /** True when [rect] is substantially covered by [box] — the bubble detector already has it. */
    private fun overlaps(rect: Rect, box: BubbleBox): Boolean {
        val overlap = Rect(rect)
        if (!overlap.intersect(box.toRect())) return false
        val area = rect.width().toLong() * rect.height()
        if (area <= 0) return true
        val covered = overlap.width().toLong() * overlap.height()
        return covered.toFloat() / area > COVERED_FRACTION
    }

    private fun BubbleBox.toRefinerBounds() = OversizedSpeechRefiner.Bounds(left, top, right, bottom)

    private fun Rect.toRefinerBounds() = OversizedSpeechRefiner.Bounds(left, top, right, bottom)

    private fun recognizerFor(sourceLanguage: String): TextRecognizer = when (sourceLanguage) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        /** Band height for reading a long strip. Comfortably inside ML Kit's input limits. */
        const val BAND_HEIGHT = 1200
        const val BAND_OVERLAP = 600
        const val BAND_EDGE_CONTEXT = 96
        const val MIN_BAND_HEIGHT = 80

        /** Letters or digits a block needs before it counts as lettering rather than noise. */
        const val MIN_BLOCK_SIDE = 40
        const val BLOCK_PAD_RATIO = 0.04f

        /** Vertical gap, as a fraction of block height, still counted as the same panel. */
        const val MERGE_GAP_RATIO = 0.8f
        /** Horizontal overlap, as a fraction of the narrower block, required to merge. */
        const val MERGE_OVERLAP_RATIO = 0.5f

        /** Fraction of a block inside a bubble box above which the bubble already covers it. */
        const val COVERED_FRACTION = 0.5f

        /**
         * Marks these boxes as detector-synthesised. Below every real detection, so reading order and
         * any confidence-based handling downstream keep preferring the model's own boxes.
         */
        const val SYNTHETIC_CONFIDENCE = 0.15f
    }
}
