package mihon.feature.translation

/**
 * Sound-effect kana that is artwork, not dialogue.
 *
 * A shout drawn as バアアア or a single ッ is lettering the reader is not meant to read as a
 * sentence. Consecutive repetition is the reliable signal; two-character words like ダメ stay.
 */
internal object JapaneseSfxGuard {

    fun shouldDrop(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        if (letters.length == 1 && isKana(letters[0])) return true
        if (letters.any { !isKana(it) && it != 'ー' && it != '～' }) return false
        val distinct = letters.filter { it != 'ー' && it != '～' }.toSet().size
        if (distinct > MAX_SFX_LETTERS) return false
        return REPEAT.containsMatchIn(letters)
    }

    private fun isKana(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x3040..0x309F || cp in 0x30A0..0x30FF
    }

    private val REPEAT = Regex("(.)\\1{2,}")
    private const val MAX_SFX_LETTERS = 2
}
