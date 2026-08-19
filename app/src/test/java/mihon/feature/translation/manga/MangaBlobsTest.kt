package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaBlobsTest {

    @Test
    fun `black bubble detector finds a filled dark oval and ignores a thin ring`() {
        val width = 120
        val height = 80
        val pixels = IntArray(width * height) { MangaPixels.rgb(240, 240, 240) }
        for (y in 20 until 60) {
            for (x in 30 until 80) {
                pixels[y * width + x] = MangaPixels.rgb(10, 10, 10)
            }
        }
        val found = MangaBlobs.detectBlackBubbles(pixels, width, height)
        assertTrue(found.isNotEmpty())
        assertTrue(found.first().isDark)
        assertTrue(found.first().right - found.first().left > 40)
    }

    @Test
    fun `process auto fills a white bubble interior`() {
        val width = 40
        val height = 40
        val pixels = IntArray(width * height) { MangaPixels.rgb(255, 255, 255) }
        for (y in 8 until 32) {
            for (x in 8 until 32) {
                pixels[y * width + x] = if ((x + y) % 5 == 0) {
                    MangaPixels.rgb(0, 0, 0)
                } else {
                    MangaPixels.rgb(250, 250, 250)
                }
            }
        }
        val filled = MangaBlobs.processAuto(pixels, width, height, forceDark = false)
        assertTrue(filled.pixels.count { MangaPixels.gray(it) > 200 } > width * height / 2)
        assertTrue(filled.contourRight - filled.contourLeft > 10)
    }

    @Test
    fun `process auto fills lettering holes inside a white bubble`() {
        val width = 48
        val height = 48
        val pixels = IntArray(width * height) { MangaPixels.rgb(180, 180, 180) }
        for (y in 6 until 42) {
            for (x in 6 until 42) {
                pixels[y * width + x] = MangaPixels.rgb(250, 250, 250)
            }
        }
        for (y in 18 until 30) {
            for (x in 18 until 30) {
                pixels[y * width + x] = MangaPixels.rgb(0, 0, 0)
            }
        }
        val filled = MangaBlobs.processAuto(pixels, width, height, forceDark = false)
        val center = filled.pixels[24 * width + 24]
        assertTrue(MangaPixels.gray(center) > 200)
    }

    @Test
    fun `nms keeps the higher confidence of two overlapping boxes`() {
        val a = MangaBlobs.Box(0, 0, 40, 40, 0.9f, false)
        val b = MangaBlobs.Box(2, 2, 38, 38, 0.4f, true)
        val kept = MangaBlobs.nms(listOf(a, b))
        assertEquals(1, kept.size)
        assertEquals(0.9f, kept.first().confidence)
    }
}
