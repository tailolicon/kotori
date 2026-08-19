package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaTextWrapTest {

    @Test
    fun `does not break words when they fit`() {
        assertEquals("xin chao\nban", MangaTextWrap.smartWrap("xin chao ban", 8))
    }

    @Test
    fun `keeps a single long word on its own line`() {
        val wrapped = MangaTextWrap.smartWrap("supercalifragilistic", 8)
        assertTrue(wrapped.lines().any { it.contains("supercalifragilistic") })
    }

    @Test
    fun `picks a font that fits the bubble`() {
        val fit = MangaTextWrap.optimalSize("xin chao", 200, 80) { line, font ->
            line.length * font * 0.6f
        }
        assertTrue(fit.fits)
        assertTrue(fit.size >= MangaTextWrap.MIN_FONT_SIZE)
        assertTrue(fit.size <= MangaTextWrap.MAX_FONT_SIZE)
        assertTrue(fit.wrapped.isNotBlank())
    }

    @Test
    fun `legible type scales with the page it came off`() {
        // The Python constants assume a roughly 1000px page. A 2069px raw needs bigger type for the
        // same apparent size, and 10px on one is invisible.
        val (smallMin, smallMax) = MangaTextWrap.boundsFor(1000)
        assertEquals(MangaTextWrap.MIN_FONT_SIZE, smallMin)
        assertEquals(MangaTextWrap.MAX_FONT_SIZE, smallMax)

        val (bigMin, bigMax) = MangaTextWrap.boundsFor(2069)
        assertTrue(bigMin > smallMin, "min was $bigMin")
        assertTrue(bigMax > smallMax, "max was $bigMax")
    }

    @Test
    fun `reports that a sentence cannot be set in a sliver`() {
        val fit = MangaTextWrap.optimalSize(
            "Ta có nghĩa vụ là người bảo hộ của con, phải đưa con về nhà trong giới hạn cho phép.",
            width = 24,
            height = 18,
            minFont = 20,
            maxFont = 120,
        ) { line, font -> line.length * font * 0.6f }
        assertFalse(fit.fits)
    }
}
