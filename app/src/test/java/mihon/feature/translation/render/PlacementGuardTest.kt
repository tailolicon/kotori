package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlacementGuardTest {

    @Test
    fun `small clipped manga bubble keeps enough room around its line`() {
        val allowed = PlacementGuard.allowed(
            evidence = listOf(bounds(45, 45, 135, 69), bounds(51, 47, 130, 67)),
            cropWidth = 180,
            cropHeight = 180,
        )!!

        assertTrue(allowed.top <= 6)
        assertTrue(allowed.bottom >= 108)
        assertTrue(allowed.left <= 51)
        assertTrue(allowed.right >= 130)
    }

    @Test
    fun `escaped page-white slot is bounded near OCR evidence`() {
        val allowed = PlacementGuard.allowed(
            evidence = listOf(bounds(60, 40, 150, 100), bounds(75, 55, 140, 90)),
            cropWidth = 400,
            cropHeight = 600,
        )!!

        assertEquals(bounds(29, 0, 181, 158), allowed)
        assertTrue(allowed.bottom < 300)
    }

    @Test
    fun `oversized detector cannot pull placement away from accepted OCR lines`() {
        val allowed = PlacementGuard.allowedFromOcrOrDetector(
            ocrEvidence = listOf(bounds(20, 120, 180, 210)),
            detectorEvidence = bounds(0, 0, 390, 580),
            cropWidth = 400,
            cropHeight = 600,
        )!!

        assertEquals(bounds(0, 16, 236, 314), allowed)
        assertTrue(allowed.right < 300)
        assertTrue(allowed.bottom < 400)
    }

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        PlacementGuard.Bounds(left, top, right, bottom)
}
