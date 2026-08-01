package mihon.feature.translation.detect

import android.graphics.Bitmap
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
        val found = ArrayList<Rect>()
        try {
            var top = 0
            while (top < bitmap.height) {
                val height = minOf(BAND_HEIGHT, bitmap.height - top)
                if (height < MIN_BAND_HEIGHT) break
                val band = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                try {
                    found += readBlocks(recognizer, band, top)
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
            .filter { rect -> existing.none { overlaps(rect, it) } }
            .filter { it.width() >= MIN_BLOCK_SIDE && it.height() >= MIN_BLOCK_SIDE }
            .map { rect ->
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
                )
            }

        if (extras.isNotEmpty()) {
            logcat { "Text-block pass added ${extras.size} region(s) the bubble detector missed" }
        }
        return extras
    }

    private fun readBlocks(recognizer: TextRecognizer, band: Bitmap, offsetY: Int): List<Rect> {
        val latch = CountDownLatch(1)
        val blocks = ArrayList<Rect>()

        recognizer.process(InputImage.fromBitmap(band, 0))
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    // Require real words: a single stray glyph recognised off artwork is noise, and
                    // turning it into a box means erasing a piece of the drawing for nothing.
                    if (block.text.count { it.isLetterOrDigit() } < MIN_BLOCK_CHARS) continue
                    val box = block.boundingBox ?: continue
                    blocks += Rect(box).apply { offset(0, offsetY) }
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
        return blocks
    }

    /**
     * Joins blocks that belong to one panel.
     *
     * ML Kit splits a status window into a block per paragraph, and translating those separately
     * loses the fact that they are one panel — each would be erased and re-lettered on its own,
     * at its own font size. Blocks close together vertically and overlapping horizontally are one.
     */
    private fun mergeNearby(blocks: List<Rect>): List<Rect> {
        if (blocks.size < 2) return blocks
        val remaining = blocks.sortedWith(compareBy({ it.top }, { it.left })).toMutableList()
        val merged = ArrayList<Rect>()

        while (remaining.isNotEmpty()) {
            val current = Rect(remaining.removeAt(0))
            var grew = true
            while (grew) {
                grew = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    val gap = other.top - current.bottom
                    val horizontal = kotlin.math.min(current.right, other.right) -
                        kotlin.math.max(current.left, other.left)
                    val narrower = kotlin.math.min(current.width(), other.width())
                    val closeEnough = gap <= current.height() * MERGE_GAP_RATIO &&
                        gap >= -current.height()
                    if (closeEnough && horizontal > narrower * MERGE_OVERLAP_RATIO) {
                        current.union(other)
                        iterator.remove()
                        grew = true
                    }
                }
            }
            merged += current
        }
        return merged
    }

    /** True when [rect] is substantially covered by [box] — the bubble detector already has it. */
    private fun overlaps(rect: Rect, box: BubbleBox): Boolean {
        val overlap = Rect(rect)
        if (!overlap.intersect(box.toRect())) return false
        val area = rect.width().toLong() * rect.height()
        if (area <= 0) return true
        val covered = overlap.width().toLong() * overlap.height()
        return covered.toFloat() / area > COVERED_FRACTION
    }

    private fun recognizerFor(sourceLanguage: String): TextRecognizer = when (sourceLanguage) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        /** Band height for reading a long strip. Comfortably inside ML Kit's input limits. */
        const val BAND_HEIGHT = 2400
        const val BAND_OVERLAP = 400
        const val MIN_BAND_HEIGHT = 80

        /** Letters or digits a block needs before it counts as lettering rather than noise. */
        const val MIN_BLOCK_CHARS = 8
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
