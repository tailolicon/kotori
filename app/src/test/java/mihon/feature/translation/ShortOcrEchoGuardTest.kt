package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShortOcrEchoGuardTest {

    @Test
    fun `an echo sharing nothing with a short reading belongs to another bubble`() {
        // The REC panel: three characters read, thirty echoed, nothing in common.
        assertTrue(ShortOcrEchoGuard.contradicts("rec", "haaah that was good that was good"))
    }

    @Test
    fun `an echo that opens with what was read is the same bubble`() {
        assertFalse(ShortOcrEchoGuard.contradicts("ah", "ah thats right"))
    }

    @Test
    fun `a two letter misread does not indict a short echo of the same sound`() {
        assertFalse(ShortOcrEchoGuard.contradicts("ww", "mhm"))
    }

    @Test
    fun `short lettering on both sides is left alone`() {
        assertFalse(ShortOcrEchoGuard.contradicts("mnh", "nngh"))
    }

    @Test
    fun `nothing read means nothing to convict with`() {
        assertFalse(ShortOcrEchoGuard.contradicts("", "haaah that was good"))
        assertFalse(ShortOcrEchoGuard.contradicts("rec", ""))
    }
}
