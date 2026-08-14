package mihon.feature.translation

/** Selects meaningful OCR dialogue that a vision provider omitted from its keyed response. */
internal object VisionFallbackSelector {

    fun missingIndices(
        translations: List<String>,
        ocrTexts: List<String>,
        speechBoxes: List<Boolean> = List(ocrTexts.size) { false },
        providerSources: List<String> = emptyList(),
    ): List<Int> =
        ocrTexts.indices.filter { index ->
            val translation = translations.getOrNull(index).orEmpty()
            val ocr = ocrTexts[index]
            val providerSource = providerSources.getOrNull(index).orEmpty()
            val source = normalized(ocr)
            val providerRead = normalized(providerSource)
            val answer = normalized(translation)
            val echoed = isEcho(answer, source) || isEcho(answer, providerRead)
            val evidence = if (ocr.any(Char::isLetterOrDigit)) ocr else providerSource
            val confirmedSpeech = speechBoxes.getOrNull(index) == true &&
                evidence.count(Char::isLetterOrDigit) >= MIN_SHORT_SPEECH_CHARS
            (translation.isBlank() || echoed) && (isMeaningfulDialogue(evidence) || confirmedSpeech)
        }

    private fun isEcho(answer: String, source: String): Boolean =
        source.isNotBlank() &&
            (answer == source ||
                (source.length >= MIN_FUZZY_CHARS && bigramDice(answer, source) >= FUZZY_ECHO_DICE))

    private fun isMeaningfulDialogue(text: String): Boolean {
        val words = text.trim().split(WHITESPACE).filter { word -> word.any(Char::isLetterOrDigit) }
        val characters = text.count(Char::isLetterOrDigit)
        // A lone stylised sound effect ("BAM", "WHOOSH") is artwork, not evidence that a vision
        // response lost dialogue. Two real words or a longer sentence/title are safe to recover.
        return characters >= MIN_CHARACTERS && (words.size >= MIN_WORDS || characters >= LONG_TEXT_CHARACTERS)
    }

    private fun normalized(text: String): String =
        text.lowercase().replace(NON_WORD, " ").replace(WHITESPACE, " ").trim()

    private fun bigramDice(a: String, b: String): Float {
        val leftText = a.replace(" ", "")
        val rightText = b.replace(" ", "")
        if (leftText.length < 2 || rightText.length < 2) {
            return if (leftText.isNotEmpty() && leftText == rightText) 1f else 0f
        }
        val left = HashSet<Int>(leftText.length)
        val right = HashSet<Int>(rightText.length)
        for (index in 0 until leftText.length - 1) {
            left += leftText[index].code * 0x10000 + leftText[index + 1].code
        }
        for (index in 0 until rightText.length - 1) {
            right += rightText[index].code * 0x10000 + rightText[index + 1].code
        }
        val shared = left.count(right::contains)
        return 2f * shared / (left.size + right.size)
    }

    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private const val MIN_CHARACTERS = 6
    private const val MIN_WORDS = 2
    private const val LONG_TEXT_CHARACTERS = 10
    private const val MIN_SHORT_SPEECH_CHARS = 2
    private const val MIN_FUZZY_CHARS = 6
    private const val FUZZY_ECHO_DICE = 0.78f
}
