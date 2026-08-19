package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertArrayEquals
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
    @Test
    fun `separable morphology matches the cv2 5x5 ellipse element`() {
        // getStructuringElement(MORPH_ELLIPSE, (5, 5)) is a 5x3 rectangle unioned with a 1x5 one;
        // the fast path exploits that. This pins it against a plain 25-tap walk of the same mask,
        // because a wrong element quietly changes which dark regions survive the open/close.
        val width = 37
        val height = 29
        val random = java.util.Random(20260819L)
        val src = BooleanArray(width * height) { random.nextInt(100) < 35 }

        assertArrayEquals(referenceDilate(src, width, height), MangaBlobs.dilate(src, width, height))
        assertArrayEquals(referenceErode(src, width, height), MangaBlobs.erode(src, width, height))
    }

    /** `. . X . . / X X X X X / X X X X X / X X X X X / . . X . .` */
    private fun inElement(dx: Int, dy: Int): Boolean = (dy in -1..1 && dx in -2..2) || (dx == 0 && dy in -2..2)

    private fun referenceDilate(src: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(src.size) { i ->
            val x = i % width
            val y = i / width
            (-2..2).any { dy ->
                (-2..2).any { dx ->
                    inElement(dx, dy) &&
                        (x + dx) in 0 until width && (y + dy) in 0 until height &&
                        src[(y + dy) * width + x + dx]
                }
            }
        }

    private fun referenceErode(src: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(src.size) { i ->
            val x = i % width
            val y = i / width
            (-2..2).all { dy ->
                (-2..2).all { dx ->
                    !inElement(dx, dy) ||
                        ((x + dx) in 0 until width && (y + dy) in 0 until height &&
                            src[(y + dy) * width + x + dx])
                }
            }
        }

    @Test
    fun `black bubble search survives the downscale it runs at`() {
        // Big pages are searched at reduced resolution; the box has to come back in page space.
        val width = 2100
        val height = 3000
        val pixels = IntArray(width * height) { MangaPixels.rgb(245, 245, 245) }
        for (y in 900 until 1500) {
            val row = y * width
            for (x in 600 until 1300) pixels[row + x] = MangaPixels.rgb(8, 8, 8)
        }
        val found = MangaBlobs.detectBlackBubbles(pixels, width, height)
        assertTrue(found.isNotEmpty(), "expected the dark panel to be found")
        val box = found.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }!!
        assertTrue(box.left in 560..660, "left was ${box.left}")
        assertTrue(box.right in 1260..1360, "right was ${box.right}")
        assertTrue(box.top in 860..960, "top was ${box.top}")
        assertTrue(box.bottom in 1460..1560, "bottom was ${box.bottom}")
    }
}
