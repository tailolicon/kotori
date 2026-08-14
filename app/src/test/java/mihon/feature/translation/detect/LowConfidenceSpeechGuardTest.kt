package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowConfidenceSpeechGuardTest {

    @Test
    fun `normal detector boxes retain existing behavior`() {
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.25f, ""))
    }

    @Test
    fun `weak boxes require convincing dialogue OCR`() {
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.209f, "YES."))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.074f, "YOU...!"))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.102f, "I..."))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.180f, "CLAIRVOYANT?"))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.18f, "A COMPLETE SENTENCE"))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.238f, "AND\nALSO"))
        assertFalse(LowConfidenceSpeechGuard.shouldKeep(0.074f, "AND ASO"))
        assertFalse(LowConfidenceSpeechGuard.shouldKeep(0.24f, ""))
        assertFalse(LowConfidenceSpeechGuard.shouldKeep(0.20f, "MHM"))
    }

    @Test
    fun `long strips reject short unpunctuated credit fragments even at high confidence`() {
        assertFalse(LowConfidenceSpeechGuard.shouldKeep(0.48f, "DO", strictShortFragment = true))
        assertFalse(LowConfidenceSpeechGuard.shouldKeep(0.48f, "J DO E:", strictShortFragment = true))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.48f, "WAIT", strictShortFragment = true))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.48f, "I...", strictShortFragment = true))
        assertTrue(LowConfidenceSpeechGuard.shouldKeep(0.48f, "RUN!", strictShortFragment = true))
    }
}
