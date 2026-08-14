package mihon.feature.translation

/** Leaves long strings of non-lexical grunts in the artwork instead of translating OCR noise. */
internal object NoisyVocalizationGuard {

    fun shouldLeaveUntouched(text: String): Boolean {
        val tokens = TOKEN.findAll(text).map { it.value.lowercase() }.toList()
        if (tokens.size < MIN_TOKENS || tokens.any { it.length > MAX_TOKEN_LENGTH }) return false
        return tokens.toSet().size <= MAX_DISTINCT_TOKENS
    }

    private val TOKEN = Regex("[A-Za-z0-9]+")
    private const val MIN_TOKENS = 4
    private const val MAX_TOKEN_LENGTH = 2
    private const val MAX_DISTINCT_TOKENS = 3
}
