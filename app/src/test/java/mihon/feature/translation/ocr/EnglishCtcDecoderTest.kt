package mihon.feature.translation.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnglishCtcDecoderTest {

    @Test
    fun `greedy decode removes blanks repeats and disallowed digits`() {
        val classes = 97
        fun scores(best: Int, disallowedDigit: Int? = null) = FloatArray(classes) { -10f }.also {
            it[best] = 5f
            if (disallowedDigit != null) it[disallowedDigit] = 9f
        }
        // Model indices: blank=0; character index is position in the trained alphabet + 1.
        val y = EnglishCtcDecoder.modelIndexOf('Y')
        val o = EnglishCtcDecoder.modelIndexOf('o')
        val u = EnglishCtcDecoder.modelIndexOf('u')
        val bang = EnglishCtcDecoder.modelIndexOf('!')
        val logits = arrayOf(
            scores(y),
            scores(y),
            scores(0),
            scores(o, disallowedDigit = 2),
            scores(u),
            scores(bang),
        )

        assertEquals("You!", EnglishCtcDecoder.decode(logits))
    }
}
