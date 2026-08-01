package mihon.feature.translation.render

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Works out where replacement text may safely be drawn.
 *
 * Running this *after* inpainting is deliberate. Once the lettering is gone the bubble interior is a
 * smooth, near-uniform field, so a simple tolerance flood finds its true shape — including tails,
 * spikes and irregular hand-drawn outlines — far more reliably than any threshold applied to the
 * original inked image.
 *
 * The returned rectangle is the largest axis-aligned rectangle that fits entirely inside that field,
 * which is the geometric guarantee that text can never spill onto the bubble outline or the artwork
 * outside it.
 */
internal object TextArea {

    /**
     * @param pixels inpainted region pixels, row-major
     * @param bounds region-local rectangle to stay inside (normally the whole region)
     * @param seed region-local point known to sit inside the bubble, usually the OCR centroid
     * @param fallback rectangle to return when no enclosed field can be identified
     */
    fun find(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: Rect,
        seed: Pair<Int, Int>,
        blocked: BooleanArray,
        fallback: Rect,
    ): Rect {
        if (bounds.width() < MIN_SIDE || bounds.height() < MIN_SIDE) return fallback

        val interior = floodInterior(pixels, width, height, bounds, seed, blocked) ?: return fallback

        val boundsArea = bounds.width().toFloat() * bounds.height()
        val coverage = interior.second / boundsArea

        // Almost the entire crop matched: there is no enclosing outline, so this is text sitting
        // directly on artwork. Keeping the original text footprint is the only safe choice — expanding
        // would push the translation over the drawing.
        if (coverage > NO_BOUNDARY_COVERAGE) return fallback

        // Barely anything matched: the seed probably landed on a glyph remnant or a tone edge.
        if (coverage < DEGENERATE_COVERAGE) return fallback

        val rect = largestInscribedRect(interior.first, width, bounds) ?: return fallback

        return if (rect.width() < MIN_SIDE || rect.height() < MIN_SIDE) {
            fallback
        } else {
            // Keep a hairline of clearance so antialiased outlines are never touched.
            Rect(rect).apply {
                inset(INTERIOR_INSET, INTERIOR_INSET)
                if (width() < MIN_SIDE || height() < MIN_SIDE) set(rect)
            }
        }
    }

    /** Returns the interior mask and its pixel count, or null when the seed is unusable. */
    private fun floodInterior(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: Rect,
        seed: Pair<Int, Int>,
        blocked: BooleanArray,
    ): Pair<BooleanArray, Int>? {
        val (seedX, seedY) = seed
        if (seedX < bounds.left || seedX >= bounds.right || seedY < bounds.top || seedY >= bounds.bottom) {
            return null
        }
        if (blocked[seedY * width + seedX]) return null

        // Average a small patch around the seed so a single stray pixel cannot define the reference.
        var r = 0
        var g = 0
        var b = 0
        var samples = 0
        for (y in max(bounds.top, seedY - SEED_PATCH)..min(bounds.bottom - 1, seedY + SEED_PATCH)) {
            for (x in max(bounds.left, seedX - SEED_PATCH)..min(bounds.right - 1, seedX + SEED_PATCH)) {
                val p = pixels[y * width + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                samples++
            }
        }
        if (samples == 0) return null
        val refR = r / samples
        val refG = g / samples
        val refB = b / samples

        val visited = BooleanArray(width * height)
        val stack = ArrayDeque<Int>()
        val start = seedY * width + seedX
        visited[start] = true
        stack.addLast(start)
        var count = 1

        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            val x = index % width
            val y = index / width

            if (x > bounds.left) count += tryVisit(pixels, blocked, visited, stack, index - 1, refR, refG, refB)
            if (x < bounds.right - 1) count += tryVisit(pixels, blocked, visited, stack, index + 1, refR, refG, refB)
            if (y > bounds.top) count += tryVisit(pixels, blocked, visited, stack, index - width, refR, refG, refB)
            if (y < bounds.bottom - 1) {
                count += tryVisit(pixels, blocked, visited, stack, index + width, refR, refG, refB)
            }
        }

        return visited to count
    }

    private fun tryVisit(
        pixels: IntArray,
        blocked: BooleanArray,
        visited: BooleanArray,
        stack: ArrayDeque<Int>,
        index: Int,
        refR: Int,
        refG: Int,
        refB: Int,
    ): Int {
        if (visited[index] || blocked[index]) return 0
        val p = pixels[index]
        val distance = max(
            abs(((p shr 16) and 0xFF) - refR),
            max(abs(((p shr 8) and 0xFF) - refG), abs((p and 0xFF) - refB)),
        )
        if (distance > FLOOD_TOLERANCE) return 0
        visited[index] = true
        stack.addLast(index)
        return 1
    }

    /**
     * Largest all-true axis-aligned rectangle, via the standard maximal-rectangle-in-histogram sweep:
     * one pass building per-column run heights, one stack pass per row. Linear in area.
     */
    fun largestInscribedRect(mask: BooleanArray, width: Int, bounds: Rect): Rect? {
        val spanWidth = bounds.width()
        if (spanWidth <= 0) return null

        val heights = IntArray(spanWidth)
        var best = 0
        var bestRect: Rect? = null

        val stack = ArrayDeque<Int>()

        for (y in bounds.top until bounds.bottom) {
            val rowBase = y * width
            for (i in 0 until spanWidth) {
                heights[i] = if (mask[rowBase + bounds.left + i]) heights[i] + 1 else 0
            }

            stack.clear()
            var i = 0
            while (i <= spanWidth) {
                val current = if (i == spanWidth) 0 else heights[i]
                if (stack.isEmpty() || current >= heights[stack.last()]) {
                    stack.addLast(i)
                    i++
                } else {
                    val top = stack.removeLast()
                    val leftBound = if (stack.isEmpty()) 0 else stack.last() + 1
                    val rectWidth = i - leftBound
                    val rectHeight = heights[top]
                    val area = rectWidth * rectHeight
                    if (area > best && rectWidth >= MIN_SIDE && rectHeight >= MIN_SIDE) {
                        best = area
                        bestRect = Rect(
                            bounds.left + leftBound,
                            y - rectHeight + 1,
                            bounds.left + leftBound + rectWidth,
                            y + 1,
                        )
                    }
                }
            }
        }

        return bestRect
    }

    private const val MIN_SIDE = 8
    private const val SEED_PATCH = 2
    private const val FLOOD_TOLERANCE = 30
    private const val INTERIOR_INSET = 2
    private const val NO_BOUNDARY_COVERAGE = 0.93f
    private const val DEGENERATE_COVERAGE = 0.06f
}
