package mihon.feature.translation.debug

import mihon.feature.translation.provider.BubbleTranslation
import mihon.feature.translation.provider.ProseContext
import mihon.feature.translation.provider.ProseTranslation
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProvider

/**
 * The provider the regression harness runs against: no network, no quota, and — the property the
 * whole suite rests on — the same input text always produces the same output text.
 *
 * Real providers cannot give that. Google Translate rewords the same sentence run to run, which is
 * exactly what made an earlier attempt to compare "ghost pixels" between two runs meaningless: the
 * changed area differed because the *words* differed, regardless of any rendering change. With this
 * provider, any pixel that differs between two runs of the same build is a behaviour change.
 *
 * The transform swaps each vowel for a heavily-marked Vietnamese one ("a" → "ạ"...), which keeps
 * string length while forcing diacritics through the renderer — the layout paths that matter for
 * Vietnamese output (line wrap, fit search, halo) all exercise as they would on real translations.
 */
class DeterministicTranslationProvider : TranslationProvider {

    override val displayName = "Regression"

    override suspend fun translateLines(
        texts: List<String>,
        context: TranslationContext,
    ): List<BubbleTranslation> = texts.map { BubbleTranslation(it, vietnamize(it)) }

    override suspend fun translateProse(
        paragraphs: List<String>,
        context: TranslationContext,
        prose: ProseContext,
    ): ProseTranslation = ProseTranslation(paragraphs.joinToString("\n\n") { vietnamize(it) })

    private fun vietnamize(text: String): String {
        // Echo what a real provider echoes. Google returns short sound effects ("FWAAH", "HMPH")
        // and letterless strings unchanged, and the pipeline's isUntranslated guard then drops the
        // bubble instead of erasing artwork to stamp a non-translation. Transforming those here
        // made the harness stamp grey patches over SFX that live output leaves untouched — the
        // audit then counted defects no user would ever see. Deterministic either way.
        val trimmed = text.trim()
        // The short-shout rule is a Latin one. CJK writing has no spaces and packs a whole line of
        // dialogue into four or five characters ("逃げろ!"), so applying it there echoed every
        // bubble on a Japanese page and the harness reported the page as having nothing to
        // translate — a defect of the fixture provider, not of the pipeline.
        val isLatin = trimmed.all { it.code < 0x2E80 }
        val isShortShout = isLatin && !trimmed.contains(Regex("\\s")) && trimmed.length <= 5
        if (isShortShout || trimmed.none { it.isLetter() }) return text
        if (isLatin) {
            return buildString(text.length) {
                for (ch in text) append(VOWELS[ch] ?: ch)
            }
        }
        // CJK has none of the Latin vowels the transform swaps, so it came back byte-identical and
        // the pipeline's isUntranslated guard correctly dropped every bubble — a Japanese page then
        // rendered as "nothing to translate" no matter how well the rest of the pipeline did. Map
        // each character through the marked-vowel alphabet by code point instead: still a pure
        // function of the input, still forces diacritics through the layout, and never an echo.
        return buildString(text.length) {
            for (ch in text) {
                if (ch.isLetter()) append(CJK_ALPHABET[ch.code % CJK_ALPHABET.length]) else append(ch)
            }
        }
    }

    private companion object {
        /** Stand-in alphabet for scripts the vowel swap cannot touch. */
        const val CJK_ALPHABET = "ạệịộựỵăâđêôơưbcdghklmnpqrstvx"

        val VOWELS = mapOf(
            'a' to 'ạ', 'e' to 'ệ', 'i' to 'ị', 'o' to 'ộ', 'u' to 'ự', 'y' to 'ỵ',
            'A' to 'Ạ', 'E' to 'Ệ', 'I' to 'Ị', 'O' to 'Ộ', 'U' to 'Ự', 'Y' to 'Ỵ',
        )
    }
}
