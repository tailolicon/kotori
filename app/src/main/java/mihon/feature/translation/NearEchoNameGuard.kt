package mihon.feature.translation

/** Prevents a provider typo from replacing a single all-caps character name. */
internal object NearEchoNameGuard {

    fun preserveSource(source: String, translated: String, sourceLanguage: String, targetLanguage: String): Boolean {
        if (sourceLanguage != "en" || targetLanguage != "vi") return false
        val sourceToken = source.trim().trim(*PUNCTUATION)
        val translatedToken = translated.trim().trim(*PUNCTUATION)
        if (!SOURCE_NAME.matches(sourceToken) || !LATIN_TOKEN.matches(translatedToken)) return false
        if (sourceToken.equals(translatedToken, ignoreCase = true)) return true
        if (sourceToken.length != translatedToken.length) return false
        return differingCharacters(sourceToken, translatedToken) <= MAX_DIFFERENCES
    }

    private fun differingCharacters(left: String, right: String): Int =
        left.indices.count { left[it].uppercaseChar() != right[it].uppercaseChar() }

    private val SOURCE_NAME = Regex("[A-Z]{4,12}")
    private val LATIN_TOKEN = Regex("[A-Za-z]{4,12}")
    private val PUNCTUATION = charArrayOf('.', ',', '!', '?', ':', ';', '\'', '"', '…')
    private const val MAX_DIFFERENCES = 1
}
