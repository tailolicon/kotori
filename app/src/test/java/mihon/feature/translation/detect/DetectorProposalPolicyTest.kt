package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectorProposalPolicyTest {

    @Test
    fun `tiny low-confidence word proposal is retained`() {
        assertTrue(DetectorProposalPolicy.shouldKeep(0.074f, 97f, 35f))
        assertTrue(DetectorProposalPolicy.shouldKeep(0.060f, 111f, 36f))
        assertTrue(DetectorProposalPolicy.shouldKeep(0.08f, 35f, 97f))
    }

    @Test
    fun `weak panels and square artwork proposals are rejected`() {
        assertFalse(DetectorProposalPolicy.shouldKeep(0.074f, 463f, 166f))
        assertFalse(DetectorProposalPolicy.shouldKeep(0.074f, 50f, 48f))
        assertFalse(DetectorProposalPolicy.shouldKeep(0.05f, 97f, 35f))
    }

    @Test
    fun `normal-confidence proposal keeps all bubble shapes`() {
        assertTrue(DetectorProposalPolicy.shouldKeep(0.10f, 300f, 300f))
    }
}
