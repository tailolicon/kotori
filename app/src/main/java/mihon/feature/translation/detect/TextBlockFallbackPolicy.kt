package mihon.feature.translation.detect

import mihon.feature.translation.ShortDialogueNormalizer

/** Keeps short OCR fragments only when the bubble detector has already proved they are dialogue. */
internal object TextBlockFallbackPolicy {

    fun shouldReadFragment(characters: Int, text: String): Boolean =
        characters >= MIN_SPEECH_CHARACTERS || ShortDialogueNormalizer.isLikelyUtterance(text)

    fun shouldKeep(characters: Int, overlapsSpeech: Boolean, text: String = ""): Boolean =
        characters >= MIN_STANDALONE_CHARACTERS ||
            (overlapsSpeech && characters >= MIN_SPEECH_CHARACTERS) ||
            ShortDialogueNormalizer.isLikelyUtterance(text)

    private const val MIN_STANDALONE_CHARACTERS = 8
    private const val MIN_SPEECH_CHARACTERS = 2
}
