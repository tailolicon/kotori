package mihon.feature.translation.ocr

import mihon.feature.translation.ShortDialogueNormalizer
import kotlin.math.abs

/** Chooses bounded crop rotations only for short Latin OCR that looks orientation-corrupted. */
internal object DeskewRetryPolicy {

    fun rotations(text: String, measuredAngle: Float): List<Float> {
        val letters = text.filter(Char::isLetter)
        if (letters.length !in 2..MAX_LETTERS || ShortDialogueNormalizer.isLikelyUtterance(text)) return emptyList()
        if (abs(measuredAngle) in MIN_ANGLE..MAX_ANGLE) return listOf(-measuredAngle)

        val firstLetter = text.firstOrNull(Char::isLetter) ?: return emptyList()
        val reversedMixedCase =
            firstLetter.isLowerCase() && letters.any(Char::isUpperCase) && text.none(Char::isWhitespace)
        return if (reversedMixedCase) listOf(FALLBACK_ANGLE, -FALLBACK_ANGLE) else emptyList()
    }

    private const val MIN_ANGLE = 7f
    private const val MAX_ANGLE = 35f
    private const val MAX_LETTERS = 15
    private const val FALLBACK_ANGLE = 20f
}
