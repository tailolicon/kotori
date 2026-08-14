package mihon.feature.translation

/** Repairs one-character OCR slips in short, unmistakable English dialogue. */
internal object ShortDialogueNormalizer {

    fun normalize(text: String): String {
        if (!isShort(text)) return text
        val compact = WORD.findAll(text).joinToString("") { it.value.lowercase() }
        val compactReplacement = if (WORD.findAll(text).count() > 1) closestDialogueWord(compact) else null
        if (compactReplacement != null) {
            val letters = text.filter(Char::isLetter)
            val replacement = when {
                letters.count(Char::isUpperCase) >= letters.count(Char::isLowerCase) -> compactReplacement.uppercase()
                letters.firstOrNull()?.isUpperCase() == true -> compactReplacement.replaceFirstChar(Char::uppercase)
                else -> compactReplacement
            }
            return replacement + text.trimEnd().takeLastWhile { !it.isLetterOrDigit() }
        }
        return WORD.replace(text) { match ->
            val replacement = closestDialogueWord(match.value.lowercase()) ?: return@replace match.value
            when {
                match.value.all(Char::isUpperCase) -> replacement.uppercase()
                match.value.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar(Char::uppercase)
                else -> replacement
            }
        }
    }

    fun isLikelyUtterance(text: String): Boolean {
        // A lone first-person pronoun followed by an ellipsis is real hesitant dialogue, not
        // decorative lettering. Requiring two letters here left the very common manga caption
        // "I..." untouched even when both detectors found it.
        if (isEllipticalFirstPerson(text)) return true
        if (!isShort(text)) return false
        val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
        return words.isNotEmpty() && words.all { closestDialogueWord(it) != null }
    }

    /** Google deliberately preserves several command/interjection words; provide their Vietnamese form. */
    fun directTranslation(text: String, sourceLanguage: String, targetLanguage: String): String? {
        if (sourceLanguage != "en" || targetLanguage != "vi") return null
        val normalized = normalize(text)
        val key = WORD.findAll(normalized).joinToString(" ") { it.value.lowercase() }
        val translated = VIETNAMESE[key] ?: return null
        val suffix = normalized.trimEnd().takeLastWhile { !it.isLetterOrDigit() }
        return translated + suffix
    }

    fun isEllipticalFirstPerson(text: String): Boolean = ELLIPTICAL_FIRST_PERSON.matches(text.trim())

    private fun isShort(text: String): Boolean =
        text.count(Char::isLetter) in 2..MAX_LETTERS && WORD.findAll(text).count() <= MAX_WORDS

    private fun closestDialogueWord(word: String): String? {
        if (word in DIALOGUE_WORDS) return word
        if (word.length < MIN_FUZZY_WORD_LENGTH) return null
        return DIALOGUE_WORDS
            .asSequence()
            .filter { kotlin.math.abs(it.length - word.length) <= 1 }
            .firstOrNull { editDistanceAtMostOne(word, it) }
    }

    private fun editDistanceAtMostOne(left: String, right: String): Boolean {
        if (kotlin.math.abs(left.length - right.length) > 1) return false
        var leftIndex = 0
        var rightIndex = 0
        var edits = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            if (left[leftIndex] == right[rightIndex]) {
                leftIndex++
                rightIndex++
            } else {
                if (++edits > 1) return false
                when {
                    left.length > right.length -> leftIndex++
                    right.length > left.length -> rightIndex++
                    else -> {
                        leftIndex++
                        rightIndex++
                    }
                }
            }
        }
        if (leftIndex < left.length || rightIndex < right.length) edits++
        return edits == 1
    }

    private val WORD = Regex("[A-Za-z]+")
    private val ELLIPTICAL_FIRST_PERSON = Regex("(?i)^i\\s*(?:\\.{2,}|…+)$")
    private val DIALOGUE_WORDS = setOf(
        "ah", "eh", "hey", "hi", "hello", "huh", "no", "oh", "oi", "ok", "okay", "ow",
        "please", "rawr", "roger", "sorry", "stop", "thanks", "thank", "umm", "ummm", "wait", "whoa", "yes",
        "clairvoyant",
        "go", "look", "listen", "me", "my", "you", "your", "we", "our", "it", "its", "is", "are",
        "am", "was", "were", "what", "where", "when", "why", "how", "well", "now", "time", "that", "this",
    )
    private val VIETNAMESE = mapOf(
        "ah" to "À",
        "no" to "KHÔNG",
        "roger" to "ĐÃ RÕ",
        "rawr" to "GỪỪ",
        "umm" to "ỪM",
        "ummm" to "ỪM",
        "wait" to "KHOAN",
        "wait no" to "KHOAN, KHÔNG",
        "whoa" to "ÔI",
        "yes" to "VÂNG",
        "now" to "NGAY BÂY GIỜ",
        "and also" to "VÀ CŨNG",
        "is me" to "LÀ TÔI",
        "i" to "TÔI",
        "clairvoyant" to "NHÀ NGOẠI CẢM",
    )
    private const val MIN_FUZZY_WORD_LENGTH = 3
    private const val MAX_LETTERS = 12
    private const val MAX_WORDS = 3
}
