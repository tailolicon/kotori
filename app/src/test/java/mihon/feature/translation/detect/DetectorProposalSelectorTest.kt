package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectorProposalSelectorTest {

    @Test
    fun `regular artwork fan-out is capped`() {
        val proposals = (0 until 40).map { index ->
            proposal(left = index * 10f, top = 100f, confidence = 0.30f - index / 1000f)
        }

        assertEquals(28, DetectorProposalSelector.keepIndices(proposals, 822, 1200).size)
    }

    @Test
    fun `strongest weak proposal is reserved in each spatial cell`() {
        val proposals = listOf(
            proposal(68f, 1062f, confidence = 0.074f),
            proposal(72f, 1070f, confidence = 0.071f),
            proposal(500f, 100f, confidence = 0.079f),
        )

        val kept = DetectorProposalSelector.keepIndices(proposals, 822, 1200)

        assertTrue(0 in kept)
        assertTrue(1 !in kept)
        assertTrue(2 in kept)
    }

    private fun proposal(left: Float, top: Float, confidence: Float) =
        DetectorProposalSelector.Proposal(left, top, left + 97f, top + 35f, confidence)
}
