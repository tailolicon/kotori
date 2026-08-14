package mihon.feature.translation.ocr

import mihon.feature.translation.ShortDialogueNormalizer

/** Limits the expensive high-contrast OCR retry to plausible missed English lettering. */
internal object BlankOcrRetryPolicy {

    fun shouldRetry(sourceLanguage: String, confidence: Float, width: Int, height: Int, text: String): Boolean {
        if (sourceLanguage != "en") return false
        if (text.isBlank()) {
            return confidence >= HIGH_CONFIDENCE ||
                (confidence >= TINY_TEXT_CONFIDENCE && width <= MAX_TINY_WIDTH && height <= MAX_TINY_HEIGHT)
        }
        val untrustedShortText = text.count(Char::isLetter) <= MAX_UNTRUSTED_LETTERS &&
            !ShortDialogueNormalizer.isLikelyUtterance(text)
        if (confidence >= HIGH_CONFIDENCE) return untrustedShortText
        val tinyProposal = confidence >= TINY_TEXT_CONFIDENCE &&
            width <= MAX_TINY_WIDTH && height <= MAX_TINY_HEIGHT
        if (!tinyProposal) return false
        // ML Kit sometimes emits two or three arbitrary letters for outlined italic words. Treating
        // every non-blank result as final prevented the independent recognizer from ever seeing YOU.
        return untrustedShortText
    }

    fun accept(confidence: Float, recovered: String): Boolean {
        if (ShortDialogueNormalizer.isLikelyUtterance(recovered)) return true
        if (confidence < HIGH_CONFIDENCE) return false
        val words = WORD.findAll(recovered).toList()
        return words.size >= 2 && words.sumOf { it.value.length } >= MIN_HIGH_CONFIDENCE_LETTERS
    }

    /** The recognition-only model has no language detector, so reject short two-line texture noise. */
    fun acceptFallback(confidence: Float, recovered: String): Boolean {
        if (ShortDialogueNormalizer.isLikelyUtterance(recovered)) return true
        if (confidence < MULTIWORD_FALLBACK_CONFIDENCE) return false
        val words = WORD.findAll(recovered).toList()
        return words.size >= 2 && words.sumOf { it.value.length } >= MIN_FALLBACK_LETTERS
    }

    private val WORD = Regex("[A-Za-z]+")
    private const val HIGH_CONFIDENCE = 0.25f
    private const val TINY_TEXT_CONFIDENCE = 0.055f
    private const val MAX_TINY_WIDTH = 180
    private const val MAX_TINY_HEIGHT = 110
    private const val MIN_HIGH_CONFIDENCE_LETTERS = 4
    private const val MIN_FALLBACK_LETTERS = 6
    private const val MAX_UNTRUSTED_LETTERS = 7
    private const val MULTIWORD_FALLBACK_CONFIDENCE = 0.15f
}
