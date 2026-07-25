package mihon.feature.novelreader.tts

/**
 * Rewrites the tone marks in Moonshine's Vietnamese IPA into the digit convention the neural voices
 * were actually trained on.
 *
 * The mismatch this bridges is why chapters sounded like a foreign language. Moonshine's Vietnamese
 * G2P reads from ipa-dict, which writes each syllable's tone as Chao tone letters — `ba˧˨` for
 * "bà". The Piper voices for Vietnamese, however, were trained on espeak-ng output, and espeak-ng
 * names its six Vietnamese tone phonemes literally `1`…`6` — its phoneme table defines
 * `phoneme 1 // ngang`, `phoneme 2 // huyền`, and so on — so the models' phoneme inventory contains
 * the ASCII digits and *no Chao letters at all*. Left alone, the synthesis pipeline drops every
 * character it does not know, which deletes the tones: the voice then reads fluent, confident,
 * toneless syllables, which to a Vietnamese ear is simply not Vietnamese.
 *
 * The digit is inserted where espeak-ng puts its tone phoneme — after the vowel nucleus, before any
 * final consonant (`baŋ˧˨` → `ba2ŋ`), which is the same convention the library itself follows when
 * it converts Mandarin's Chao letters to digits.
 *
 * Contours are classified by shape rather than listed exhaustively, because the dictionary spells
 * one tone differently by syllable type — sắc is `˨˦` in an open syllable but `˦˥` in a checked
 * one — and an unlisted variant would otherwise fall back to silence again.
 */
object VietnameseTonePhonemes {

    /** Chao tone letters, low to high: ˩ ˨ ˧ ˦ ˥ (U+02E9..U+02E5). */
    private val TONE_LETTERS = mapOf('˩' to 1, '˨' to 2, '˧' to 3, '˦' to 4, '˥' to 5)

    /** Glottalization marks the dictionary embeds inside ngã/nặng contours. */
    private const val MODIFIER_GLOTTAL = 'ˀ'
    private const val GLOTTAL_STOP = 'ʔ'

    /** Consonants that can close a Vietnamese syllable; the tone digit goes before them. */
    private val FINAL_CONSONANTS = setOf('m', 'n', 'ŋ', 'p', 't', 'k', '͡')

    /**
     * Converts one sentence of dictionary IPA to espeak-style tone digits.
     *
     * Anything that is not a tone contour passes through untouched, so a sentence that mixes
     * Vietnamese with untoned loanwords still reads.
     */
    fun toEspeakTones(ipa: String): String {
        val out = StringBuilder(ipa.length)
        val syllable = StringBuilder()
        ipa.forEach { char ->
            if (char == ' ' || char == '-' || char == '\n') {
                out.append(syllable.convertSyllable())
                syllable.setLength(0)
                out.append(char)
            } else {
                syllable.append(char)
            }
        }
        out.append(syllable.convertSyllable())
        return out.toString()
    }

    private fun StringBuilder.convertSyllable(): String {
        val syllable = toString()
        if (syllable.isEmpty()) return syllable

        // The tone run sits at the end of the syllable in dictionary IPA: tone letters possibly
        // interleaved with ˀ and closed by ʔ. Anything after the first tone letter is the contour.
        val first = syllable.indexOfFirst { it in TONE_LETTERS }
        if (first < 0) return syllable
        val contour = syllable.substring(first)
            .filter { it in TONE_LETTERS || it == MODIFIER_GLOTTAL || it == GLOTTAL_STOP }
        val body = buildString {
            append(syllable, 0, first)
            syllable.substring(first).forEach { char ->
                if (char !in TONE_LETTERS && char != MODIFIER_GLOTTAL && char != GLOTTAL_STOP) {
                    append(char)
                }
            }
        }
        val digit = contour.toToneDigit() ?: return body

        // espeak places the tone right after the vowel: skip back over the final consonants so
        // `baŋ` + huyền becomes `ba2ŋ`, while an open syllable simply gets the digit appended.
        var insertion = body.length
        while (insertion > 0 && body[insertion - 1] in FINAL_CONSONANTS) insertion--
        // A syllable with no vowel at all (degenerate input) keeps the digit at its end.
        if (insertion == 0) insertion = body.length
        return buildString {
            append(body, 0, insertion)
            append(digit)
            append(body, insertion, body.length)
        }
    }

    /**
     * Names the tone from the shape of its contour.
     *
     * Glottalized contours are ngã (rising, `5`) or nặng (falling, `6`); a three-letter dip is hỏi
     * (`4`); otherwise the direction between first and last letter separates sắc (`3`), huyền
     * (`2`) and ngang (`1`).
     */
    private fun String.toToneDigit(): Char? {
        val heights = mapNotNull { TONE_LETTERS[it] }
        if (heights.isEmpty()) return null
        val glottal = any { it == MODIFIER_GLOTTAL || it == GLOTTAL_STOP }
        return when {
            glottal -> if (heights.last() >= heights.first()) '5' else '6'
            heights.size >= 3 -> '4'
            heights.last() > heights.first() -> '3'
            heights.last() < heights.first() -> '2'
            else -> '1'
        }
    }
}
