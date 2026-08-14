package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoisyVocalizationGuardTest {

    @Test
    fun `repeated short OCR grunts remain part of artwork`() {
        assertTrue(NoisyVocalizationGuard.shouldLeaveUntouched("00 00 HO HH HH HH!!"))
        assertTrue(NoisyVocalizationGuard.shouldLeaveUntouched("OO OO OH HH"))
    }

    @Test
    fun `real short dialogue is still translated`() {
        assertFalse(NoisyVocalizationGuard.shouldLeaveUntouched("WAIT NO!!"))
        assertFalse(NoisyVocalizationGuard.shouldLeaveUntouched("IS ME..."))
        assertFalse(NoisyVocalizationGuard.shouldLeaveUntouched("HA HA HA"))
    }
}
