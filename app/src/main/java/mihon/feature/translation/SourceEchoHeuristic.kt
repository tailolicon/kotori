package mihon.feature.translation

/** Finds obvious English left in a Vietnamese translation without misclassifying proper names. */
internal object SourceEchoHeuristic {

    fun isLikely(text: String, sourceLanguage: String, targetLanguage: String): Boolean {
        if (sourceLanguage !in ENGLISH_OR_AUTO || targetLanguage != "vi") return false
        // Correct Vietnamese normally carries precomposed accented letters. Besides being a strong
        // target-language signal, rejecting them before ASCII tokenisation prevents "Tôi" becoming
        // the one-letter English pronoun "i" after the accented character is split away.
        if (text.any { character -> character.isLetter() && character.code > ASCII_MAX }) return false
        val words = text.lowercase().split(NON_LETTER).filter(String::isNotBlank)
        if (words.isEmpty()) return false
        // Vietnamese translations can legitimately retain names and terms in Latin script. Require
        // a strong English function word/interjection, or a recognisable inflected signal, rather
        // than treating all unaccented Latin text as untranslated.
        return words.any(STRONG_SIGNALS::contains) ||
            words.any { word -> PREFIX_SIGNALS.any(word::startsWith) }
    }

    private val STRONG_SIGNALS = setOf(
        "yes", "no", "wait", "whoa", "roger", "ah", "umm", "ummm",
        "i", "i'll", "me", "my", "you", "your", "we", "our", "they", "their",
        "is", "are", "am", "was", "were", "it", "its", "it's",
        "what", "where", "when", "why", "how", "have", "has", "had",
        "do", "did", "does", "well", "now", "time", "that", "this",
    )
    private val PREFIX_SIGNALS = setOf("enem", "clairvoy", "understand", "everyon")
    private val ENGLISH_OR_AUTO = setOf("en", "auto")
    private val NON_LETTER = Regex("[^a-z']+")
    private const val ASCII_MAX = 0x7f
}
