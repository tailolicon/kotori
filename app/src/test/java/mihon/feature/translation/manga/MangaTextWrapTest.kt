package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertEquals
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
        val (size, _, wrapped) = MangaTextWrap.optimalSize("xin chao", 200, 80) { line, font ->
            line.length * font * 0.6f
        }
        assertTrue(size >= MangaTextWrap.MIN_FONT_SIZE)
        assertTrue(size <= MangaTextWrap.MAX_FONT_SIZE)
        assertTrue(wrapped.isNotBlank())
    }
}
