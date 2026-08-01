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
        val clamped = box.clampTo(bitmap.width, bitmap.height)
        if (!clamped.isUsable()) return BubbleText("", emptyList())

        val crop = Bitmap.createBitmap(bitmap, clamped.left, clamped.top, clamped.width, clamped.height)
        val upscale = upscaleFactor(clamped.width, clamped.height)
        val input = if (upscale > 1) {
            Bitmap.createScaledBitmap(crop, clamped.width * upscale, clamped.height * upscale, true)
        } else {
            crop
        }

        val result = try {
            recognizeBlocking(recognizer, input, clamped.left, clamped.top, upscale)
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
    ): BubbleText {
        val latch = CountDownLatch(1)
        var text = ""
        var lines = emptyList<TextLineBox>()

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                text = visionText.text.trim()
                lines = visionText.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        // Prefer element boxes: they hug the glyphs, whereas a line box on vertical
                        // Japanese text can span the full bubble height including empty margin.
                        val elementBoxes = line.elements.mapNotNull { it.boundingBox }
                        val rects = elementBoxes.ifEmpty { listOfNotNull(line.boundingBox) }
                        if (rects.isEmpty()) {
                            null
                        } else {
                            rects.map { rect -> TextLineBox(rect.mapBack(offsetX, offsetY, upscale), line.text) }
                        }
                    }
                    .flatten()
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
    }
}
