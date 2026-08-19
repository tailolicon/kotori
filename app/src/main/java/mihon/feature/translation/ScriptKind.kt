package mihon.feature.translation

/** Dominant writing system of a recognised string. Used to re-read a mixed-language region. */
enum class ScriptKind {
    JAPANESE,
    KOREAN,
    CHINESE,
    LATIN,
    NONE,
}

/**
 * Classifies a string by the script of its letters.
 *
 * A page (or even one balloon) can mix Korean and Spanish, or Japanese raw inside an English
 * series. The page-level recogniser then returns junk for the other script; this is how a region
 * asks to be read again with the matching one.
 */
internal object ScriptKindDetector {

    fun of(text: String): ScriptKind {
        var japanese = 0
        var korean = 0
        var han = 0
        var latin = 0
        for (ch in text) {
            val cp = ch.code
            when {
                cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF -> japanese++
                cp in 0x1100..0x11FF || cp in 0xAC00..0xD7AF -> korean++
                cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF -> han++
                ch.isLetter() && cp < 0x0250 -> latin++
            }
        }
        val total = japanese + korean + han + latin
        if (total == 0) return ScriptKind.NONE
        return when {
            japanese > 0 && japanese + han >= korean && japanese + han >= latin -> ScriptKind.JAPANESE
            korean > 0 && korean >= latin && korean >= japanese -> ScriptKind.KOREAN
            han > 0 && han >= latin && japanese == 0 && korean == 0 -> ScriptKind.CHINESE
            latin > 0 && latin >= japanese && latin >= korean && latin >= han -> ScriptKind.LATIN
            else -> ScriptKind.NONE
        }
    }

    /** Language code the on-device recogniser should use for [kind], or null if none applies. */
    fun languageCode(kind: ScriptKind): String? = when (kind) {
        ScriptKind.JAPANESE -> "ja"
        ScriptKind.KOREAN -> "ko"
        ScriptKind.CHINESE -> "zh"
        ScriptKind.LATIN -> "en"
        ScriptKind.NONE -> null
    }

    fun ofLanguage(language: String): ScriptKind = when (language) {
        "ja" -> ScriptKind.JAPANESE
        "ko" -> ScriptKind.KOREAN
        "zh" -> ScriptKind.CHINESE
        else -> ScriptKind.LATIN
    }

    /**
     * True when [text] does not look like a real reading in [expected] — leftover symbols from
     * pointing the wrong recogniser at the region.
     */
    fun looksLikeJunk(text: String, expected: ScriptKind): Boolean {
        val letters = text.count { it.isLetter() }
        val cjk = text.count(::isCjk)
        if (letters < 2 && cjk == 0) return true
        val symbols = text.count { !it.isLetterOrDigit() && !it.isWhitespace() && it != '…' && it != '—' }
        if (letters > 0 && symbols > letters) return true
        val kind = of(text)
        if (kind == ScriptKind.NONE || kind == expected) return false
        // Han-only Japanese (大丈夫, 了解) classifies as Chinese; that is not a misread.
        if (expected == ScriptKind.JAPANESE && kind == ScriptKind.CHINESE) return false
        if (expected == ScriptKind.CHINESE && kind == ScriptKind.JAPANESE) return false
        return true
    }

    /**
     * Whether two readings may belong to one balloon. Japanese and Chinese share Han, so they
     * stay together; Korean and Latin on the same strip are two balloons even when they sit
     * next to each other.
     */
    fun sameWritingSystem(a: ScriptKind, b: ScriptKind): Boolean {
        if (a == ScriptKind.NONE || b == ScriptKind.NONE) return true
        if (a == b) return true
        if (a == ScriptKind.JAPANESE && b == ScriptKind.CHINESE) return true
        if (a == ScriptKind.CHINESE && b == ScriptKind.JAPANESE) return true
        return false
    }

    private fun isCjk(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x3040..0x30FF || cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF ||
            cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF
    }
}
