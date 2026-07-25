package mihon.feature.novelreader.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Inputs are real entries from the Vietnamese lexicon the G2P reads (ipa-dict, Northern), and the
 * expected outputs follow espeak-ng's convention — tone digit `1`…`6` after the vowel nucleus —
 * because that is what the neural voices saw in training.
 */
class VietnameseTonePhonemesTest {

    @Test
    fun `maps each of the six tones to its espeak digit`() {
        // ngang, huyền, sắc (open), hỏi, ngã, nặng (open).
        assertEquals("a1", VietnameseTonePhonemes.toEspeakTones("a˧˧"))
        assertEquals("a2", VietnameseTonePhonemes.toEspeakTones("a˧˨"))
        assertEquals("a3", VietnameseTonePhonemes.toEspeakTones("a˨˦"))
        assertEquals("a4", VietnameseTonePhonemes.toEspeakTones("a˧˩˨"))
        assertEquals("noj5", VietnameseTonePhonemes.toEspeakTones("noj˧ˀ˥"))
        assertEquals("a6", VietnameseTonePhonemes.toEspeakTones("a˨ˀ˩ʔ"))
    }

    @Test
    fun `places the digit before a final consonant like espeak does`() {
        // "bàng" → huyền on a syllable closed by ŋ: the digit belongs after the vowel.
        assertEquals("ba2ŋ", VietnameseTonePhonemes.toEspeakTones("baŋ˧˨"))
        // "phật" → nặng in a checked syllable (˨ˀ˩ with no ʔ).
        assertEquals("fɤ̆6t", VietnameseTonePhonemes.toEspeakTones("fɤ̆t˨ˀ˩"))
        // "sít"-type checked sắc is written ˦˥ rather than ˨˦; the shape rule still reads rising.
        assertEquals("si3t", VietnameseTonePhonemes.toEspeakTones("sit˦˥"))
    }

    @Test
    fun `keeps multi-syllable words and sentences aligned`() {
        assertEquals(
            "a1 ba2ŋ",
            VietnameseTonePhonemes.toEspeakTones("a˧˧ baŋ˧˨"),
        )
        // Hyphenated loanwords keep their hyphens; each syllable converts independently.
        assertEquals(
            "a1-zo3t",
            VietnameseTonePhonemes.toEspeakTones("a˧˧-zot˦˥"),
        )
    }

    @Test
    fun `leaves untoned text untouched`() {
        assertEquals("ok", VietnameseTonePhonemes.toEspeakTones("ok"))
        assertEquals("", VietnameseTonePhonemes.toEspeakTones(""))
    }

    @Test
    fun `emits no tone letters or glottal marks the model would drop`() {
        val converted = VietnameseTonePhonemes.toEspeakTones(
            "ɛŋ˧˧ huŋ͡m˧˨ lɯk˨ˀ˩ lɯəŋ˨ˀ˩ʔ vu˧ˀ˥ caŋ˧˧",
        )
        listOf('˥', '˦', '˧', '˨', '˩', 'ˀ', 'ʔ').forEach { forbidden ->
            assertFalse(converted.contains(forbidden), "$converted must not contain $forbidden")
        }
    }

    @Test
    fun `keeps the labio-velar tie bar out of the digit position`() {
        // "hùng" → huŋ͡m: the tie-bar cluster ŋ͡m is one final; the digit goes before all of it.
        assertEquals("hu2ŋ͡m", VietnameseTonePhonemes.toEspeakTones("huŋ͡m˧˨"))
    }
}
