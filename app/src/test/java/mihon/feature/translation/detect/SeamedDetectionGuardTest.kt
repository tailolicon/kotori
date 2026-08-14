package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeamedDetectionGuardTest {

    @Test
    fun `page-local strip detection is never allowed to replace page-aligned manga geometry`() {
        val kept = SeamedDetectionGuard.supplementalIndices(
            continuous = listOf(bounds(16, 5175, 743, 5397)),
            pageAligned = listOf(bounds(480, 5150, 620, 5430)),
            seams = intArrayOf(4800, 6000),
        )

        assertEquals(emptyList<Int>(), kept)
    }

    @Test
    fun `unsupported manhwa bubble crossing a source seam is retained`() {
        val kept = SeamedDetectionGuard.supplementalIndices(
            continuous = listOf(bounds(500, 1160, 760, 1260)),
            pageAligned = emptyList(),
            seams = intArrayOf(1200, 2400),
        )

        assertEquals(listOf(0), kept)
    }

    @Test
    fun `cross-seam duplicate yields to a reliable page-aligned half`() {
        val kept = SeamedDetectionGuard.supplementalIndices(
            continuous = listOf(bounds(500, 1160, 760, 1260)),
            pageAligned = listOf(bounds(520, 1165, 740, 1198)),
            seams = intArrayOf(1200, 2400),
        )

        assertEquals(emptyList<Int>(), kept)
    }

    @Test
    fun `compact page-local bubble missed by page pass is recovered from strip detection`() {
        val kept = SeamedDetectionGuard.supplementalIndices(
            continuous = listOf(bounds(678, 433, 774, 468)),
            pageAligned = listOf(bounds(590, 0, 778, 283), bounds(496, 621, 760, 850)),
            seams = intArrayOf(1200, 2400),
        )

        assertEquals(listOf(0), kept)
    }

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        SeamedDetectionGuard.Bounds(left, top, right, bottom)
}
