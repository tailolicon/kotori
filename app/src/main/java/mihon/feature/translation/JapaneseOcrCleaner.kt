package mihon.feature.translation

/**
 * Conservative repairs for recurring Japanese manga OCR slips.
 *
 * These are script-level substitutions ML Kit makes on any page — a missing dakuten on ござい,
 * 心 read as 今 next to 配 — not vocabulary from one title. Anything that needs a dictionary to
 * decide is left alone; the vision provider sees the artwork and is the real reader.
 */
internal object JapaneseOcrCleaner {

    fun clean(text: String): String {
        if (text.none(::isJapaneseLetter)) return text
        var cleaned = text
        for ((from, to) in REPAIRS) {
            cleaned = cleaned.replace(from, to)
        }
        return CJK_INTERNAL_SPACE.replace(cleaned, "")
    }

    private fun isJapaneseLetter(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x3040..0x30FF || cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF
    }

    private val REPAIRS = listOf(
        "こざいません" to "ございません",
        "こざいます" to "ございます",
        "今配ない" to "心配ない",
    )

    /** Spaces ML Kit inserts between CJK glyphs of one word. */
    private val CJK_INTERNAL_SPACE = Regex(
        "(?<=[\\u3040-\\u30FF\\u3400-\\u9FFF\\uF900-\\uFAFF])\\s+" +
            "(?=[\\u3040-\\u30FF\\u3400-\\u9FFF\\uF900-\\uFAFF])",
    )
}
