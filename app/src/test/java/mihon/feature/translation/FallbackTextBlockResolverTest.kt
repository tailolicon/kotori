package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FallbackTextBlockResolverTest {

    @Test
    fun `blank speech crop yields to overlapping whole-page OCR`() {
        val kept = FallbackTextBlockResolver.keepIndices(
            listOf(
                candidate(40, 900, 330, 1200, speech = true, fallback = false, text = ""),
                candidate(55, 1010, 310, 1180, speech = false, fallback = true, text = "I AM IRRITATED"),
            ),
        )

        assertEquals(listOf(1), kept)
    }

    @Test
    fun `readable speech crop keeps bubble geometry and drops fallback duplicate`() {
        val kept = FallbackTextBlockResolver.keepIndices(
            listOf(
                candidate(500, 200, 760, 500, speech = true, fallback = false, text = "REAL DIALOGUE"),
                candidate(550, 280, 710, 430, speech = false, fallback = true, text = "REAL DIALOGUE"),
            ),
        )

        assertEquals(listOf(0), kept)
    }

    @Test
    fun `substantially more complete whole-page OCR replaces a readable fragment`() {
        val kept = FallbackTextBlockResolver.keepIndices(
            listOf(
                candidate(40, 900, 330, 1200, speech = true, fallback = false, text = "ACTUALLY IRRITATED"),
                candidate(
                    55,
                    1010,
                    310,
                    1180,
                    speech = false,
                    fallback = true,
                    text = "I'M ACTUALLY IRRITATED BY HIM NOT JEALOUS OR ANYTHING",
                ),
            ),
        )

        assertEquals(listOf(1), kept)
    }

    @Test
    fun `small OCR wording difference does not discard speech geometry`() {
        val kept = FallbackTextBlockResolver.keepIndices(
            listOf(
                candidate(500, 200, 760, 500, speech = true, fallback = false, text = "REAL DIALOGUE HERE"),
                candidate(550, 280, 710, 430, speech = false, fallback = true, text = "REAL DIALOG HERE"),
            ),
        )

        assertEquals(listOf(0), kept)
    }

    @Test
    fun `independent caption is never treated as a speech fallback`() {
        val kept = FallbackTextBlockResolver.keepIndices(
            listOf(candidate(20, 20, 200, 90, speech = false, fallback = false, text = "NARRATION")),
        )

        assertEquals(listOf(0), kept)
    }

    private fun candidate(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        speech: Boolean,
        fallback: Boolean,
        text: String,
    ) = FallbackTextBlockResolver.Candidate(left, top, right, bottom, speech, fallback, text)
}
