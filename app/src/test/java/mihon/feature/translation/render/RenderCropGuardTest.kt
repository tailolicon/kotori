package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RenderCropGuardTest {

    @Test
    fun `accepted top line expands crop beyond a low manga detector`() {
        val crop = RenderCropGuard.bounds(
            detector = bounds(678, 433, 774, 468),
            ocrLines = listOf(
                bounds(686, 377, 766, 391),
                bounds(688, 438, 764, 452),
            ),
            detectorPad = 48,
            imageWidth = 822,
            pageTop = 0,
            pageBottom = 1200,
        )

        assertEquals(bounds(630, 373, 822, 516), crop)
    }

    @Test
    fun `neighbour page OCR cannot expand a manga crop through the seam`() {
        val crop = RenderCropGuard.bounds(
            detector = bounds(0, 904, 322, 1182),
            ocrLines = listOf(bounds(20, 1020, 180, 1080), bounds(40, 1208, 210, 1230)),
            detectorPad = 160,
            imageWidth = 822,
            pageTop = 0,
            pageBottom = 1200,
        )

        assertEquals(bounds(0, 744, 482, 1200), crop)
    }

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        RenderCropGuard.Bounds(left, top, right, bottom)
}
