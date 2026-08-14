package mihon.feature.translation.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
import mihon.feature.translation.ShortDialogueNormalizer
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2

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
class BubbleTextRecognizer(context: Context) {

    private val englishFallback = lazy { EnglishMangaOcrFallback(context) }

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
            boxes.map { box -> recognizeBubble(recognizer, bitmap, box, sourceLanguage) }
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun recognizeBubble(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        box: BubbleBox,
        sourceLanguage: String,
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
            val padY = SpeechLineSelector.verticalCropPadding(box.width, box.height)
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
            val first = recognizeBlocking(
                recognizer,
                input,
                clamped.left,
                clamped.top,
                upscale,
                if (box.isTextBlock) null else box.toRect(),
            )
            val contrastImproved = if (
                BlankOcrRetryPolicy.shouldRetry(
                    sourceLanguage,
                    box.confidence,
                    box.width,
                    box.height,
                    first.text,
                )
            ) {
                // Retry the detector's exact lettering box, not the padded speech crop. Padding is
                // useful for finding missing lines on the first pass, but on tiny italic words it
                // pulls a jagged balloon edge and nearby face into the retry until the four letters
                // occupy too little of the image for ML Kit to recognise (the page-21 YOU case).
                val retryCrop = tightRetryCrop(bitmap, box)
                val retryInput = upscaleRetryInput(retryCrop)
                val highContrast = highContrastCopy(retryInput)
                try {
                    val highContrastText = recognizeTextOnlyBlocking(recognizer, highContrast)
                    val acceptedHighContrast = highContrastText
                        .takeIf { BlankOcrRetryPolicy.accept(box.confidence, it) }
                    if (acceptedHighContrast != null) {
                        acceptedHighContrast
                    } else {
                        // The recognition-only model performs its own fixed-height resize. Feeding
                        // it the ML Kit enlargement would resize the lettering twice and blur thin
                        // italic strokes such as the two-line AND ALSO inset.
                        val fallbackText = englishFallback.value.recognize(retryCrop)
                        fallbackText.takeIf { BlankOcrRetryPolicy.acceptFallback(box.confidence, it) }
                    }
                } finally {
                    highContrast.recycle()
                    if (retryInput !== retryCrop) retryInput.recycle()
                    if (retryCrop !== bitmap) retryCrop.recycle()
                }
            } else {
                null
            }
            val ellipticalContext = if (
                contrastImproved == null &&
                EllipticalContextRetryPolicy.shouldRetry(
                    sourceLanguage,
                    box.confidence,
                    box.width,
                    box.height,
                )
            ) {
                recognizeEllipticalContext(recognizer, bitmap, box)
            } else {
                null
            }
            val rotations = if (sourceLanguage == "en") {
                DeskewRetryPolicy.rotations(
                    ellipticalContext?.text ?: contrastImproved ?: first.text,
                    ellipticalContext?.angleDegrees ?: first.angleDegrees,
                )
            } else {
                emptyList()
            }
            val improved = rotations.firstNotNullOfOrNull { rotation ->
                val matrix = Matrix().apply { postRotate(rotation) }
                val deskewed = Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
                try {
                    recognizeTextOnlyBlocking(recognizer, deskewed)
                        .takeIf(ShortDialogueNormalizer::isLikelyUtterance)
                } finally {
                    if (deskewed !== input) deskewed.recycle()
                }
            }
            if (contrastImproved != null) logcat { "Recovered blank OCR as '$contrastImproved' with high contrast" }
            if (ellipticalContext != null) logcat { "Recovered shallow OCR as '${ellipticalContext.text}' with context" }
            if (improved != null) logcat { "Deskewed short OCR '${contrastImproved ?: first.text}' to '$improved'" }
            BubbleText(
                improved ?: ellipticalContext?.text ?: contrastImproved ?: first.text,
                ellipticalContext?.lines ?: first.lines,
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
    ): RecognitionPass {
        val latch = CountDownLatch(1)
        var text = ""
        var lines = emptyList<TextLineBox>()
        var angleDegrees = 0f

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val angles = mutableListOf<Float>()
                val candidates = visionText.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        line.cornerPoints?.takeIf { it.size >= 2 }?.let { corners ->
                            val dx = corners[1].x - corners[0].x
                            val dy = corners[1].y - corners[0].y
                            if (dx != 0) angles += Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        }
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
                    val geometry = candidates.map { line ->
                        SpeechLineSelector.Bounds(
                            left = line.rect.left,
                            top = line.rect.top,
                            right = line.rect.right,
                            bottom = line.rect.bottom,
                        )
                    }
                    val boxGeometry = SpeechLineSelector.Bounds(
                        left = speechBox.left,
                        top = speechBox.top,
                        right = speechBox.right,
                        bottom = speechBox.bottom,
                    )
                    SpeechLineSelector.select(geometry, boxGeometry).map(candidates::get)
                }
                // The crop may contain neighbouring lettering, so visionText.text is not safe once
                // speech OCR is expanded. Rebuild the source strictly from accepted line geometry.
                text = lines.joinToString("\n") { it.text.trim() }.trim()
                if (angles.isNotEmpty()) angleDegrees = angles.sorted()[angles.size / 2]
                latch.countDown()
            }
            .addOnFailureListener { error ->
                logcat { "Text recognition failed: ${error.message}" }
                latch.countDown()
            }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logcat { "Text recognition timed out after ${TIMEOUT_SECONDS}s" }
        }
        return RecognitionPass(text, lines, angleDegrees)
    }

    private fun recognizeTextOnlyBlocking(recognizer: TextRecognizer, bitmap: Bitmap): String {
        val latch = CountDownLatch(1)
        var text = ""
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                text = visionText.textBlocks
                    .flatMap { it.lines }
                    .joinToString("\n") { line ->
                        line.elements.joinToString(" ") { it.text.trim() }.ifBlank { line.text }
                    }
                    .trim()
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return text
    }

    /** Otsu binarisation makes thin, antialiased comic lettering legible to ML Kit. */
    private fun highContrastCopy(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        pixels.forEach { color ->
            val gray = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            histogram[gray]++
        }

        val total = pixels.size.toLong().coerceAtLeast(1L)
        val weightedTotal = histogram.indices.sumOf { it.toLong() * histogram[it] }
        var backgroundWeight = 0L
        var backgroundSum = 0L
        var bestVariance = -1.0
        var threshold = 127
        for (value in histogram.indices) {
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0L) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0L) break
            backgroundSum += value.toLong() * histogram[value]
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight
            val foregroundMean = (weightedTotal - backgroundSum).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = value
            }
        }

        pixels.indices.forEach { index ->
            val color = pixels[index]
            val gray = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            pixels[index] = if (gray <= threshold) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun tightRetryCrop(source: Bitmap, box: BubbleBox): Bitmap {
        val tight = box.copy(
            left = box.left - TIGHT_RETRY_PADDING,
            top = box.top - TIGHT_RETRY_PADDING,
            right = box.right + TIGHT_RETRY_PADDING,
            bottom = box.bottom + TIGHT_RETRY_PADDING,
        ).clampTo(source.width, source.height)
        return Bitmap.createBitmap(source, tight.left, tight.top, tight.width, tight.height)
    }

    private fun recognizeEllipticalContext(
        recognizer: TextRecognizer,
        source: Bitmap,
        box: BubbleBox,
    ): RecognitionPass? {
        val padX = EllipticalContextRetryPolicy.horizontalPadding(box.width)
        val padY = EllipticalContextRetryPolicy.verticalPadding(box.width)
        val context = box.copy(
            left = box.left - padX,
            top = box.top - padY,
            right = box.right + padX,
            bottom = box.bottom + padY,
        ).clampTo(source.width, source.height)
        if (!context.isUsable()) return null

        val crop = Bitmap.createBitmap(source, context.left, context.top, context.width, context.height)
        val input = upscaleRetryInput(crop)
        return try {
            val pass = recognizeBlocking(
                recognizer = recognizer,
                bitmap = input,
                offsetX = context.left,
                offsetY = context.top,
                upscale = input.width / crop.width,
                speechBox = box.toRect(),
            ).takeIf { ShortDialogueNormalizer.isEllipticalFirstPerson(it.text) } ?: return null

            // The detector found only the baseline while contextual OCR found mainly the ellipsis.
            // Their union is the real line. Keeping OCR's partial geometry alone makes the renderer
            // erase the dots but leave the large initial I beside the Vietnamese replacement.
            val fullLine = Rect(box.toRect())
            pass.lines.forEach { fullLine.union(it.rect) }
            pass.copy(lines = listOf(TextLineBox(fullLine, pass.text)))
        } finally {
            if (input !== crop) input.recycle()
            if (crop !== source) crop.recycle()
        }
    }

    private fun upscaleRetryInput(crop: Bitmap): Bitmap {
        val shortSide = minOf(crop.width, crop.height).coerceAtLeast(1)
        val scale = (TIGHT_RETRY_TARGET_SHORT_SIDE + shortSide - 1) / shortSide
        val boundedScale = scale.coerceIn(TIGHT_RETRY_MIN_SCALE, TIGHT_RETRY_MAX_SCALE)
        return Bitmap.createScaledBitmap(
            crop,
            crop.width * boundedScale,
            crop.height * boundedScale,
            true,
        )
    }

    private data class RecognitionPass(
        val text: String,
        val lines: List<TextLineBox>,
        val angleDegrees: Float,
    )

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
        const val BUBBLE_OCR_PAD_X_RATIO = 0.16f
        const val BUBBLE_OCR_PAD_MIN = 12
        const val BUBBLE_OCR_PAD_X_MAX = 48
        const val TIGHT_RETRY_PADDING = 8
        const val TIGHT_RETRY_TARGET_SHORT_SIDE = 160
        const val TIGHT_RETRY_MIN_SCALE = 3
        const val TIGHT_RETRY_MAX_SCALE = 5
    }

    fun close() {
        if (englishFallback.isInitialized()) englishFallback.value.close()
    }
}
