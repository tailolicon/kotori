package mihon.feature.translation.ocr

/** Allows one broad OCR crop for a detector box that caught only the baseline of `I...`. */
internal object EllipticalContextRetryPolicy {
    fun shouldRetry(sourceLanguage: String, confidence: Float, width: Int, height: Int): Boolean =
        sourceLanguage == "en" &&
            confidence >= MIN_CONFIDENCE &&
            width in MIN_WIDTH..MAX_WIDTH &&
            height > 0 &&
            height * MAX_HEIGHT_TO_WIDTH <= width

    fun horizontalPadding(width: Int): Int = width.coerceIn(MIN_PADDING, MAX_HORIZONTAL_PADDING)

    fun verticalPadding(width: Int): Int = width.coerceIn(MIN_PADDING, MAX_VERTICAL_PADDING)

    private const val MIN_CONFIDENCE = 0.08f
    private const val MIN_WIDTH = 48
    private const val MAX_WIDTH = 180
    private const val MAX_HEIGHT_TO_WIDTH = 2
    private const val MIN_PADDING = 48
    private const val MAX_HORIZONTAL_PADDING = 140
    private const val MAX_VERTICAL_PADDING = 120
}
