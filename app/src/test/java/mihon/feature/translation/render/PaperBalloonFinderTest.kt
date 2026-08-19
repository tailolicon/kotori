package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperBalloonFinderTest {

    @Test
    fun `a vertical column grows sideways much more than up and down`() {
        val area = PaperBalloonFinder.searchArea(
            textLeft = 320,
            textTop = 140,
            textRight = 400,
            textBottom = 314,
            pageWidth = 1100,
            pageHeight = 1600,
            vertical = true,
        )
        val growX = 320 - area.left
        val growY = 140 - area.top
        assertTrue(growX > growY * 2, "sideways $growX should dwarf vertical $growY")
        assertTrue(area.width > 80 * 3)
        assertTrue(area.height < 174 * 2)
    }

    @Test
    fun `a horizontal line grows up and down more than sideways`() {
        val area = PaperBalloonFinder.searchArea(
            textLeft = 200,
            textTop = 400,
            textRight = 500,
            textBottom = 450,
            pageWidth = 900,
            pageHeight = 1300,
            vertical = false,
        )
        val growX = 200 - area.left
        val growY = 400 - area.top
        val textWidth = 300
        val textHeight = 50
        assertTrue(
            growY.toFloat() / textHeight > growX.toFloat() / textWidth,
            "vertical fraction ${growY / textHeight.toFloat()} should exceed sideways ${growX / textWidth.toFloat()}",
        )
        assertTrue(area.height > textHeight * 2)
    }

    @Test
    fun `search stays on the page`() {
        val area = PaperBalloonFinder.searchArea(
            textLeft = 10,
            textTop = 10,
            textRight = 40,
            textBottom = 200,
            pageWidth = 200,
            pageHeight = 300,
            vertical = true,
        )
        assertEquals(0, area.left)
        assertTrue(area.top >= 0)
        assertTrue(area.right <= 200)
        assertTrue(area.bottom <= 300)
    }

    @Test
    fun `aspect test matches the renderer`() {
        assertTrue(PaperBalloonFinder.isVertical(80, 174))
        assertFalse(PaperBalloonFinder.isVertical(220, 80))
    }
}
