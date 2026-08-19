package mihon.feature.translation.render

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides whether lettering that sits outside any speech balloon can be erased without wrecking the
 * drawing under it.
 *
 * Manga narration is frequently set straight onto the artwork, with no balloon around it. Replacing
 * it means repainting the area it covers, and that is only honest where the area is one flat tone —
 * a white gutter, a paper margin, a character's white sleeve. Over hatching, screentone or a face,
 * the repaint is a rectangle of the wrong colour across the drawing, which is exactly the "mảng
 * trắng loang lổ" a reader notices before they notice the translation.
 *
 * So: sample what the lettering is sitting *on* — the pixels around the recognised glyph lines, not
 * the glyphs themselves — and only allow the erase where that background is uniform.
 */
internal object FlatBackgroundGuard {

    /** A recognised glyph rectangle, in crop coordinates. Plain ints so this stays unit-testable. */
    data class Line(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * @param pixels ARGB pixels of the crop
     * @param textLines recognised glyph rectangles, in crop coordinates
     * @return true when the area behind [textLines] is uniform enough to repaint
     */
    fun canRepaint(
        pixels: IntArray,
        width: Int,
        height: Int,
        textLines: List<Line>,
    ): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        if (textLines.isEmpty()) return false

        // Glyph strokes are not background, and antialiasing carries their colour a pixel or two
        // past the reported box, so the sample skips a margin around every line.
        val excluded = textLines.map { line ->
            Line(
                line.left - GLYPH_MARGIN,
                line.top - GLYPH_MARGIN,
                line.right + GLYPH_MARGIN,
                line.bottom + GLYPH_MARGIN,
            )
        }

        val samples = ArrayList<Int>(MAX_SAMPLES)
        var step = 1
        val area = width.toLong() * height
        if (area > MAX_SAMPLES) step = sqrt(area.toDouble() / MAX_SAMPLES).toInt().coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            val row = y * width
            while (x < width) {
                if (excluded.none { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) {
                    samples += pixels[row + x]
                }
                x += step
            }
            y += step
        }
        if (samples.size < MIN_SAMPLES) return false

        val reference = medianColor(samples)
        var near = 0
        var sumSq = 0.0
        for (color in samples) {
            val d = distance(color, reference)
            if (d <= TOLERANCE) {
                near++
                sumSq += d.toDouble() * d
            }
        }
        if (near == 0) return false
        val share = near.toFloat() / samples.size
        if (share < MIN_FLAT_SHARE) return false
        // A share this high can still be a soft gradient, which repaints as a visible flat patch.
        return sqrt(sumSq / near) <= MAX_DEVIATION
    }

    private fun medianColor(samples: List<Int>): Int {
        val r = IntArray(samples.size) { samples[it] shr 16 and 0xFF }
        val g = IntArray(samples.size) { samples[it] shr 8 and 0xFF }
        val b = IntArray(samples.size) { samples[it] and 0xFF }
        r.sort()
        g.sort()
        b.sort()
        val mid = samples.size / 2
        return (0xFF shl 24) or (r[mid] shl 16) or (g[mid] shl 8) or b[mid]
    }

    private fun distance(a: Int, b: Int): Int =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    /** Pixels around a glyph box that still carry its antialiasing. */
    private const val GLYPH_MARGIN = 2

    /** Sum-of-channels distance still counted as the same tone. */
    private const val TOLERANCE = 24

    /** Fraction of the background that must sit within [TOLERANCE] of the median. */
    private const val MIN_FLAT_SHARE = 0.88f

    /** RMS spread among those pixels; a gradient exceeds this even at a high share. */
    private const val MAX_DEVIATION = 10.0

    private const val MIN_SAMPLES = 64
    private const val MAX_SAMPLES = 20_000
}
