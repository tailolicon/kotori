package mihon.feature.translation.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechLineSelectorTest {

    @Test
    fun `expanded manga crop excludes next balloon in the same column`() {
        val lines = listOf(
            line(620, 70, 748, 92),
            line(625, 98, 744, 120),
            line(630, 126, 739, 148),
            line(682, 382, 760, 404),
            line(687, 410, 755, 432),
        )

        val selected = SpeechLineSelector.select(lines, line(590, 0, 778, 283))

        assertEquals(listOf(0, 1, 2), selected)
    }

    @Test
    fun `upper lines clipped by detector remain in the same speech cluster`() {
        val lines = listOf(
            line(100, 80, 180, 98),
            line(90, 104, 190, 122),
            line(105, 128, 175, 146),
        )

        val selected = SpeechLineSelector.select(lines, line(92, 118, 188, 150))

        assertEquals(listOf(0, 1, 2), selected)
    }

    @Test
    fun `horizontal neighbour is not assigned to speech box`() {
        val lines = listOf(
            line(100, 100, 180, 120),
            line(260, 102, 340, 122),
        )

        val selected = SpeechLineSelector.select(lines, line(90, 90, 190, 135))

        assertEquals(listOf(0), selected)
    }

    @Test
    fun `short detector box gets vertical crop room from its width`() {
        assertEquals(62, SpeechLineSelector.verticalCropPadding(boxWidth = 96, boxHeight = 35))
    }

    private fun line(left: Int, top: Int, right: Int, bottom: Int) =
        SpeechLineSelector.Bounds(left, top, right, bottom)
}
