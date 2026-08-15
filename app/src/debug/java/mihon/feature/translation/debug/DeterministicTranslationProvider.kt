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
        val isShortShout = !trimmed.contains(Regex("\\s")) && trimmed.length <= 5
        if (isShortShout || trimmed.none { it.isLetter() }) return text
        return buildString(text.length) {
            for (ch in text) append(VOWELS[ch] ?: ch)
        }
    }

    private companion object {
        val VOWELS = mapOf(
            'a' to 'ạ', 'e' to 'ệ', 'i' to 'ị', 'o' to 'ộ', 'u' to 'ự', 'y' to 'ỵ',
            'A' to 'Ạ', 'E' to 'Ệ', 'I' to 'Ị', 'O' to 'Ộ', 'U' to 'Ự', 'Y' to 'Ỵ',
        )
    }
}
