package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OversizedSpeechRefinerTest {

    @Test
    fun `panel-wide false positive yields to precise narration block`() {
        assertTrue(
            OversizedSpeechRefiner.shouldReplace(
                bounds(200, 304, 675, 477),
                bounds(555, 315, 700, 430),
            ),
        )
    }

    @Test
    fun `ordinary speech balloon keeps its detector geometry`() {
        assertFalse(
            OversizedSpeechRefiner.shouldReplace(
                bounds(500, 200, 760, 500),
                bounds(555, 270, 700, 420),
            ),
        )
    }

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        OversizedSpeechRefiner.Bounds(left, top, right, bottom)
}
