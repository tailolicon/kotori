package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechDuplicateResolverTest {

    @Test
    fun `same sentence in nested detector boxes keeps tight crop`() {
        val kept = SpeechDuplicateResolver.keepIndices(
            listOf(
                candidate(0, 1029, 277, 1198, "OUR MIS5ION IS TO EXTERMINATE THE ENEMIES"),
                candidate(30, 1018, 144, 1071, "OUR MISSION IS TO EXTERMINATE THE ENEMIES"),
            ),
        )

        assertEquals(listOf(1), kept)
    }

    @Test
    fun `nested balloons with different dialogue both survive`() {
        val kept = SpeechDuplicateResolver.keepIndices(
            listOf(
                candidate(0, 100, 300, 400, "LET'S GO"),
                candidate(50, 150, 200, 300, "WAIT FOR ME"),
            ),
        )

        assertEquals(listOf(0, 1), kept)
    }

    @Test
    fun `text blocks are not deduplicated by speech rule`() {
        val kept = SpeechDuplicateResolver.keepIndices(
            listOf(
                candidate(0, 0, 300, 100, "IT'S TIME", speech = false),
                candidate(10, 10, 200, 80, "IT'S TIME", speech = false),
            ),
        )

        assertEquals(listOf(0, 1), kept)
    }

    @Test
    fun `overlapping fragments with the same long reading keep broad clean geometry`() {
        val kept = SpeechDuplicateResolver.keepIndices(
            listOf(
                candidate(583, 312, 705, 373, "THEIR WAY OF LIFE IS ALSO A MYSTERY BUT WE KNOW THEY ARE HARMFUL TO HUMANS"),
                candidate(589, 359, 705, 402, "THEIR WAY OF LIFE IS ALSO A MYSTERY BUT WE KNOW THEY ARE HARMFUL TO HUMANS"),
                candidate(605, 400, 705, 428, "LIPE I5 ALSO A S MYSTERY BUT WE KNOW THEY ARE HARMFUL TO HUMANS"),
            ),
        )

        assertEquals(listOf(0), kept)
    }

    private fun candidate(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        text: String,
        speech: Boolean = true,
    ) = SpeechDuplicateResolver.Candidate(left, top, right, bottom, speech, text)
}
