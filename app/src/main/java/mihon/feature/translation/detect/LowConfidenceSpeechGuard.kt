package mihon.feature.translation.detect

import mihon.feature.translation.ShortDialogueNormalizer

/** Requires textual evidence before a low-confidence detector candidate may affect the artwork. */
internal object LowConfidenceSpeechGuard {

    fun shouldKeep(confidence: Float, text: String, strictShortFragment: Boolean = false): Boolean {
        val letters = text.count(Char::isLetter)
        if (
            strictShortFragment &&
            letters in 1..MAX_UNPUNCTUATED_FRAGMENT_LETTERS &&
            text.none(SENTENCE_PUNCTUATION::contains) &&
            !ShortDialogueNormalizer.isLikelyUtterance(text)
        ) {
            return false
        }
        if (confidence >= REGULAR_CONFIDENCE) return true
        val words = WORD.findAll(text).count()
        val credibleCompactPhrase = confidence >= COMPACT_PHRASE_CONFIDENCE &&
            words >= MIN_COMPACT_WORDS && letters >= MIN_COMPACT_PHRASE_LETTERS
        return letters >= MIN_LONG_DIALOGUE_LETTERS || credibleCompactPhrase ||
            ShortDialogueNormalizer.isLikelyUtterance(text)
    }

    private const val REGULAR_CONFIDENCE = 0.25f
    private const val COMPACT_PHRASE_CONFIDENCE = 0.15f
    private const val MIN_LONG_DIALOGUE_LETTERS = 8
    private const val MIN_COMPACT_WORDS = 2
    private const val MIN_COMPACT_PHRASE_LETTERS = 6
    private const val MAX_UNPUNCTUATED_FRAGMENT_LETTERS = 7
    private val WORD = Regex("[A-Za-z]+")
    private val SENTENCE_PUNCTUATION = setOf('.', '?', '!', '…')
}
