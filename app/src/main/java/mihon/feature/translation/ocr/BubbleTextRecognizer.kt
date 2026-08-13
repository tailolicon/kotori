package mihon.feature.translation.ocr

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
import mihon.feature.translation.model.BubbleText
import mihon.feature.translation.model.TextLineBox
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device text recognition (ML Kit) for detected bubbles.
 *
 * Two things are extracted per bubble: the recognised string, and the glyph geometry. The geometry is
 * needed even when a vision model does the actual reading, because the renderer masks *glyph pixels*
 * rather than whole bubbles — that is what keeps bubble outlines, screentones and colour artwork
 * untouched.
 *
 * Blocking; call from a background dispatcher.
 */
class BubbleTextRecognizer {

    /**
     * Recognises text inside each bubble.
     *
     * Crops are upscaled when small: ML Kit's detector has a minimum glyph height and manga furigana
     * or thin `i`/`l` strokes are routinely below it at native resolution.
     */
    fun recognize(
        bitmap: Bitmap,
        boxes: List<BubbleBox>,
        sourceLanguage: String,
    ): List<BubbleText> {
        if (boxes.isEmpty()) return emptyList()

        val recognizer = recognizerFor(sourceLanguage)
        return try {
            boxes.map { box -> recognizeBubble(recognizer, bitmap, box) }
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun recognizeBubble(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        box: BubbleBox,
    ): BubbleText {
        // The detector is trained to locate a bubble, but on compact manga lettering it often locks
        // onto only the lower lines of text. OCRing that tight rectangle loses the upper line entirely
        // (for example, "AND" above "WITHOUT FURTHER ADO"), so neither translation nor erasure can
        // recover it. Read a padded crop for real speech bubbles. Synthetic text blocks already come
        // from whole-page OCR and are padded by TextBlockDetector, so expanding those again would pull
        // neighbouring captions into the same result.
        val readBox = if (box.isTextBlock) {
            box
        } else {
            val padX = (box.width * BUBBLE_OCR_PAD_X_RATIO).toInt()
                .coerceIn(BUBBLE_OCR_PAD_MIN, BUBBLE_OCR_PAD_X_MAX)
            val padY = (box.height * BUBBLE_OCR_PAD_Y_RATIO).toInt()
                .coerceIn(BUBBLE_OCR_PAD_MIN, BUBBLE_OCR_PAD_Y_MAX)
            box.copy(
                left = box.left - padX,
                top = box.top - padY,
                right = box.right + padX,
                bottom = box.bottom + padY,
            )
        }
        val clamped = readBox.clampTo(bitmap.width, bitmap.height)
        if (!clamped.isUsable()) return BubbleText("", emptyList())

        val crop = Bitmap.createBitmap(bitmap, clamped.left, clamped.top, clamped.width, clamped.height)
        // Base the scale on the detector's original box rather than the padded read crop. Padding
        // must not make small manga lettering look "large enough" and silently reduce OCR detail.
        val upscale = upscaleFactor(box.width, box.height)
        val input = if (upscale > 1) {
            Bitmap.createScaledBitmap(crop, clamped.width * upscale, clamped.height * upscale, true)
        } else {
            crop
        }

        val result = try {
            recognizeBlocking(
                recognizer,
                input,
                clamped.left,
                clamped.top,
                upscale,
                if (box.isTextBlock) null else box.toRect(),
            )
        } finally {
            if (input !== crop) input.recycle()
            // A box covering the whole page makes createBitmap return the page itself; recycling it
            // would destroy the bitmap the caller is still translating.
            if (crop !== bitmap) crop.recycle()
        }
        return result
    }

    private fun recognizeBlocking(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        upscale: Int,
        speechBox: Rect?,
    ): BubbleText {
        val latch = CountDownLatch(1)
        var text = ""
        var lines = emptyList<TextLineBox>()

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val candidates = visionText.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        // Keep the line envelope as geometry. Word/element boxes are often clipped
                        // at both ends by ML Kit; erasing only those small boxes leaves the first or
                        // last English glyph visible underneath the translation. The line envelope
                        // covers the complete baseline while still staying tight vertically. This
                        // is especially important for outlined manga captions on dark panels.
                        val rect = line.boundingBox ?: line.elements
                            .mapNotNull { it.boundingBox }
                            .reduceOrNull { acc, next -> Rect(acc).apply { union(next) } }
                        if (rect == null) {
                            null
                        } else {
                            // `line.text` occasionally glues visually separated words together on
                            // outlined manga captions ("Intheyear"). Elements retain those word
                            // boundaries, which materially improves both translation and wrapping.
                            val lineText = line.elements.joinToString(" ") { it.text.trim() }
                                .ifBlank { line.text }
                            listOf(TextLineBox(rect.mapBack(offsetX, offsetY, upscale), lineText))
                        }
                    }
                    .flatten()
                lines = if (speechBox == null) {
                    candidates
                } else {
                    candidates.filter { line -> belongsToSpeechBox(line.rect, speechBox) }
                }
                // The crop may contain neighbouring lettering, so visionText.text is not safe once
                // speech OCR is expanded. Rebuild the source strictly from accepted line geometry.
                text = lines.joinToString("\n") { it.text.trim() }.trim()
                latch.countDown()
            }
            .addOnFailureListener { error ->
                logcat { "Text recognition failed: ${error.message}" }
                latch.countDown()
            }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logcat { "Text recognition timed out after ${TIMEOUT_SECONDS}s" }
        }
        return BubbleText(text, lines)
    }

    private fun Rect.mapBack(offsetX: Int, offsetY: Int, upscale: Int) = Rect(
        offsetX + left / upscale,
        offsetY + top / upscale,
        offsetX + right / upscale,
        offsetY + bottom / upscale,
    )

    /** A vertically adjacent line belongs to this balloon only when it shares its horizontal lane. */
    private fun belongsToSpeechBox(line: Rect, box: Rect): Boolean {
        val overlap = minOf(line.right, box.right) - maxOf(line.left, box.left)
        val narrower = minOf(line.width(), box.width()).coerceAtLeast(1)
        val horizontalShare = overlap.coerceAtLeast(0).toFloat() / narrower
        return horizontalShare >= MIN_SPEECH_HORIZONTAL_OVERLAP
    }

    private fun upscaleFactor(width: Int, height: Int): Int {
        val shortSide = minOf(width, height)
        return when {
            shortSide >= 160 -> 1
            shortSide >= 80 -> 2
            else -> 3
        }
    }

    private fun recognizerFor(sourceLanguage: String): TextRecognizer = when (sourceLanguage) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val BUBBLE_OCR_PAD_X_RATIO = 0.16f
        const val BUBBLE_OCR_PAD_Y_RATIO = 0.85f
        const val BUBBLE_OCR_PAD_MIN = 12
        const val BUBBLE_OCR_PAD_X_MAX = 48
        const val BUBBLE_OCR_PAD_Y_MAX = 180
        const val MIN_SPEECH_HORIZONTAL_OVERLAP = 0.55f
    }
}
