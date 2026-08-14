package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HorizontalSeamGuardTest {

    @Test
    fun `manga bubble near page bottom stays above source seam`() {
        val segment = HorizontalSeamGuard.segment(
            evidence = HorizontalSeamGuard.Span(top = 904, bottom = 1182),
            seams = intArrayOf(1200, 2400),
            totalHeight = 3600,
        )

        assertEquals(HorizontalSeamGuard.Span(0, 1200), segment)
    }

    @Test
    fun `ordinary box after seam stays on its own page`() {
        val segment = HorizontalSeamGuard.segment(
            evidence = HorizontalSeamGuard.Span(top = 1250, bottom = 1410),
            seams = intArrayOf(1200, 2400),
            totalHeight = 3600,
        )

        assertEquals(HorizontalSeamGuard.Span(1200, 2400), segment)
    }

    @Test
    fun `genuine split manhwa detector box may cross source seam`() {
        val segment = HorizontalSeamGuard.segment(
            evidence = HorizontalSeamGuard.Span(top = 1160, bottom = 1240),
            seams = intArrayOf(1200, 2400),
            totalHeight = 3600,
        )

        assertNull(segment)
    }
}
