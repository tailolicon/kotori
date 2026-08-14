package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptionFragmentGuardTest {

    @Test
    fun `nearby bottom-row manga balloons remain independent`() {
        assertFalse(
            CaptionFragmentGuard.shouldAbsorb(
                speech = bounds(47, 2220, 174, 2322),
                textBlock = bounds(357, 2210, 490, 2314),
                textSlot = bounds(350, 2189, 496, 2335),
            ),
        )
    }

    @Test
    fun `narrow gutter between adjacent balloons is not caption ownership`() {
        assertFalse(
            CaptionFragmentGuard.shouldAbsorb(
                speech = bounds(50, 9307, 157, 9354),
                textBlock = bounds(192, 9273, 342, 9345),
                textSlot = bounds(204, 9259, 317, 9359),
            ),
        )
    }

    @Test
    fun `detector fragment reaching prepared caption slot is absorbed`() {
        assertTrue(
            CaptionFragmentGuard.shouldAbsorb(
                speech = bounds(170, 100, 220, 140),
                textBlock = bounds(40, 90, 180, 150),
                textSlot = bounds(35, 80, 210, 160),
            ),
        )
    }

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        CaptionFragmentGuard.Bounds(left, top, right, bottom)
}
