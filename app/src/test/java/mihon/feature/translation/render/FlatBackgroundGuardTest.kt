package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlatBackgroundGuardTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `narration on a white margin may be repainted`() {
        val width = 200
        val height = 120
        val pixels = IntArray(width * height) { rgb(252, 252, 252) }
        // Lettering: a dark band the guard must exclude from its sample.
        for (y in 40 until 70) {
            for (x in 30 until 170) pixels[y * width + x] = rgb(12, 12, 12)
        }
        assertTrue(
            FlatBackgroundGuard.canRepaint(pixels, width, height, listOf(FlatBackgroundGuard.Line(30, 40, 170, 70))),
        )
    }

    @Test
    fun `narration over drawn artwork is left alone`() {
        val width = 200
        val height = 120
        // Hatching: alternating light and dark, which repaints as a flat slab across the drawing.
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            if ((x / 3) % 2 == 0) rgb(240, 240, 240) else rgb(40, 40, 40)
        }
        for (y in 40 until 70) {
            for (x in 30 until 170) pixels[y * width + x] = rgb(12, 12, 12)
        }
        assertFalse(
            FlatBackgroundGuard.canRepaint(pixels, width, height, listOf(FlatBackgroundGuard.Line(30, 40, 170, 70))),
        )
    }

    @Test
    fun `a gradient is not flat even though most of it is close to the median`() {
        val width = 200
        val height = 120
        val pixels = IntArray(width * height) { i ->
            val y = i / width
            val v = 200 + y / 4
            rgb(v, v, v)
        }
        for (y in 40 until 70) {
            for (x in 30 until 170) pixels[y * width + x] = rgb(12, 12, 12)
        }
        assertFalse(
            FlatBackgroundGuard.canRepaint(pixels, width, height, listOf(FlatBackgroundGuard.Line(30, 40, 170, 70))),
        )
    }

    @Test
    fun `a region with no recognised lettering is never repainted`() {
        val width = 40
        val height = 40
        val pixels = IntArray(width * height) { rgb(250, 250, 250) }
        assertFalse(FlatBackgroundGuard.canRepaint(pixels, width, height, emptyList()))
    }
}
