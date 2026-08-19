package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuriganaGuardTest {

    @Test
    fun `thin kana beside a kanji column is ruby`() {
        val host = FuriganaGuard.Line("道場の娘", 1370, 466, 1479, 763)
        val ruby = FuriganaGuard.Line("かんのり", 1412, 474, 1439, 569)
        assertEquals(setOf(1), FuriganaGuard.dropIndices(listOf(host, ruby)))
    }

    @Test
    fun `two dialogue columns are both kept`() {
        val left = FuriganaGuard.Line("おはよう", 318, 139, 398, 313)
        val right = FuriganaGuard.Line("今日はいい天気ですね", 988, 161, 1068, 334)
        assertTrue(FuriganaGuard.dropIndices(listOf(left, right)).isEmpty())
    }

    @Test
    fun `a short kana sentence is not ruby on its own`() {
        assertTrue(FuriganaGuard.isRubyScript("かんのり"))
        assertFalse(FuriganaGuard.isRubyScript("私はお前の保護者"))
        assertFalse(FuriganaGuard.isRubyScript("Hello"))
    }

    @Test
    fun `horizontal ruby above a host is dropped`() {
        val host = FuriganaGuard.Line("一歳しか変わらない", 100, 80, 400, 130, stroke = 28)
        val ruby = FuriganaGuard.Line("いっさい", 120, 50, 220, 72, stroke = 12)
        assertEquals(setOf(1), FuriganaGuard.dropIndices(listOf(host, ruby)))
    }

    @Test
    fun `a neighbouring kana balloon is not ruby`() {
        val host = FuriganaGuard.Line("私はお前の保護者", 500, 400, 620, 900, stroke = 28)
        val neighbour = FuriganaGuard.Line("おはよう", 80, 140, 180, 320, stroke = 26)
        assertTrue(FuriganaGuard.dropIndices(listOf(host, neighbour)).isEmpty())
    }
}
