package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextBlockFallbackPolicyTest {

    @Test
    fun `short dialogue survives when it overlaps a detected speech balloon`() {
        assertTrue(TextBlockFallbackPolicy.shouldKeep(characters = 2, overlapsSpeech = true))
        assertTrue(TextBlockFallbackPolicy.shouldKeep(characters = 3, overlapsSpeech = true))
        assertTrue(TextBlockFallbackPolicy.shouldKeep(characters = 6, overlapsSpeech = true))
    }

    @Test
    fun `short standalone artwork lettering stays excluded`() {
        assertFalse(TextBlockFallbackPolicy.shouldKeep(characters = 2, overlapsSpeech = false))
        assertFalse(TextBlockFallbackPolicy.shouldKeep(characters = 7, overlapsSpeech = false))
        assertTrue(TextBlockFallbackPolicy.shouldKeep(characters = 8, overlapsSpeech = false))
    }

    @Test
    fun `recognisable short dialogue survives without a detected balloon outline`() {
        assertTrue(TextBlockFallbackPolicy.shouldKeep(6, overlapsSpeech = false, text = "WAIT, NO!!"))
        assertFalse(TextBlockFallbackPolicy.shouldKeep(3, overlapsSpeech = false, text = "MHM"))
    }

    @Test
    fun `elliptical first-person fragment reaches whole-page OCR pipeline`() {
        assertTrue(TextBlockFallbackPolicy.shouldReadFragment(1, "I..."))
        assertFalse(TextBlockFallbackPolicy.shouldReadFragment(1, "I"))
        assertFalse(TextBlockFallbackPolicy.shouldReadFragment(1, "X"))
    }
}
