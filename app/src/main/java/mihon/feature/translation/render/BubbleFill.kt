package mihon.feature.translation.render

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds a speech bubble's interior as a filled region.
 *
 * This is the path the reference implementation takes, and for an ordinary bubble it is simply
 * better than masking individual glyphs: a bubble interior is one flat colour, so repainting the
 * whole interior with that colour erases the lettering completely, leaves no ragged halo where a
 * glyph mask ended, and gives the renderer the bubble's true shape to fit text into.
 *
 * Glyph masking still earns its place — it is the only way to handle text sitting directly on
 * artwork, where there is no interior to repaint — so [detect] returns null whenever the region it
 * finds is not flat enough to repaint safely, and the caller falls back to inpainting.
 */
internal object BubbleFill {

    /**
     * @param region interior pixels, already pulled back from the outline
     * @param bounds bounding box of [region]
     * @param fillColor colour to repaint the interior with
     * @param textColor colour sampled from the original lettering
     */
    class Result(
        val region: BooleanArray,
        val bounds: Rect,
        val fillColor: Int,
        val textColor: Int,
    )

    /**
     * @param textLines OCR line rectangles in region coordinates. Ink overlapping one of these is
     *   lettering by definition and is never mistaken for the bubble's outline.
     */
    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        searchArea: Rect,
        textLines: List<Rect>,
    ): Result? {
        if (searchArea.width() < MIN_SIDE || searchArea.height() < MIN_SIDE) return null

        val fillColor = dominantBackground(pixels, width, searchArea) ?: return null

        // Everything close to the background colour is candidate interior. Working in colour distance
        // rather than a fixed grey threshold is what lets this handle coloured and tinted bubbles,
        // which a threshold tuned for white paper misses entirely.
        //
        // Deliberately bounded by the search area rather than followed out to the bubble's true edge.
        // Letting the interior grow across the whole crop does fix the case where the detector's box
        // covers only the top of a tall bubble, but a bubble outline is not a watertight boundary:
        // wherever it is broken, antialiased or merely touches a pale patch of artwork, the flood
        // escapes and swallows the crop. Measured on a real page it produced a region of 1258x835 for
        // a box of 1073x557 — a flat slab painted straight over the artwork. A partly-erased bubble is
        // a much smaller fault than that.
        val candidate = BooleanArray(width * height)
        forEachIn(searchArea, width) { index ->
            candidate[index] = distance(pixels[index], fillColor) <= INTERIOR_TOLERANCE
        }

        val component = largestComponent(candidate, width, height, searchArea) ?: return null
        val area = searchArea.width().toFloat() * searchArea.height()
        if (component.size < area * MIN_INTERIOR_RATIO) return null

        val region = BooleanArray(width * height)
        for (index in component) region[index] = true

        // Close the lettering back up: glyphs are holes punched in the interior, and they are part of
        // the area to repaint — but the bubble's own outline must survive it.
        val structure = structuralInk(pixels, fillColor, width, searchArea, textLines)
        fillHoles(region, width, height, searchArea, structure)

        var filled = 0
        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) if (region[base + x]) filled++
        }
        val swallowsBox = filled > area * MAX_INTERIOR_RATIO

        val textColor = sampleInk(pixels, region, fillColor) ?: return null

        // Pull back from the outline so repainting cannot touch it. This is the step whose absence
        // showed up as a smeared fringe around every bubble.
        val pulledBack = erode(region, width, height, OUTLINE_CLEARANCE)
        val bounds = boundsOf(pulledBack, width) ?: return null
        if (bounds.width() < MIN_SIDE || bounds.height() < MIN_SIDE) return null

        if (!isFlat(pixels, pulledBack, fillColor, swallowsBox)) return null

        return Result(pulledBack, bounds, fillColor, textColor)
    }

    /**
     * Most frequent colour in the middle of the box, ignoring lettering.
     *
     * Quantised mode rather than mean: a bubble is mostly one colour with dark glyphs on it, and the
     * mean of those two lands on a grey that belongs to neither.
     */
    fun dominantBackground(pixels: IntArray, width: Int, searchArea: Rect): Int? {
        val insetX = (searchArea.width() * CENTRE_INSET).toInt()
        val insetY = (searchArea.height() * CENTRE_INSET).toInt()
        val centre = Rect(
            searchArea.left + insetX,
            searchArea.top + insetY,
            searchArea.right - insetX,
            searchArea.bottom - insetY,
        )
        if (centre.width() < 4 || centre.height() < 4) return null

        val counts = HashMap<Int, Int>()
        forEachIn(centre, width) { index ->
            val p = pixels[index]
            // Quantise to 3 bits per channel so near-identical shades vote together.
            val key = ((p shr 21) and 0x7 shl 6) or ((p shr 13) and 0x7 shl 3) or ((p shr 5) and 0x7)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return null

        // Average the real pixels in the winning bucket to recover full precision.
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        forEachIn(centre, width) { index ->
            val p = pixels[index]
            val key = ((p shr 21) and 0x7 shl 6) or ((p shr 13) and 0x7 shl 3) or ((p shr 5) and 0x7)
            if (key == best) {
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return null
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Ink colour: the pixels inside the region that differ most from the fill. */
    private fun sampleInk(pixels: IntArray, region: BooleanArray, fillColor: Int): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (i in region.indices) {
            if (!region[i]) continue
            if (distance(pixels[i], fillColor) < INK_SEPARATION) continue
            r += (pixels[i] shr 16) and 0xFF
            g += (pixels[i] shr 8) and 0xFF
            b += pixels[i] and 0xFF
            n++
        }
        if (n < MIN_INK_SAMPLES) return null
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /**
     * True when the region really is one flat colour apart from its lettering.
     *
     * Guards the case where the detector box lands on a gradient panel: flat-filling that would paint
     * a visible slab over the artwork, so the caller should inpaint instead.
     *
     * The background share alone cannot make that call. It assumes lettering is a small minority of
     * the region, which is true of ordinary dialogue and false of display type — a title card or a
     * heavy scanlation banner covers close to half its own box, scores like artwork, and gets pushed
     * onto the glyph-masking path where it is reliably mangled. What actually separates the two is how
     * the non-background pixels are distributed: ink sits far from the paper colour and leaves a clear
     * gap, whereas artwork drifts away from it gradually and fills that gap in.
     *
     * So a comfortable background majority still passes on its own, exactly as before; below that, the
     * region has to show the gap before it is trusted.
     *
     * @param swallowsBox region covers essentially the whole search area, so no enclosing outline was
     *   found. Harmless for a bubble whose box sits inside it, but it is also what text lying directly
     *   on artwork looks like, so those regions must earn their way through on ink separation.
     */
    private fun isFlat(
        pixels: IntArray,
        region: BooleanArray,
        fillColor: Int,
        swallowsBox: Boolean,
    ): Boolean {
        var near = 0
        var total = 0
        var sumSq = 0.0
        val far = ArrayList<Int>()
        for (i in region.indices) {
            if (!region[i]) continue
            total++
            val d = distance(pixels[i], fillColor)
            if (d <= INTERIOR_TOLERANCE) {
                near++
                sumSq += d.toDouble() * d
            } else {
                far.add(d)
            }
        }
        if (total == 0 || near == 0) return false

        // The background itself must be uniform whichever branch decides. A gradient fails here.
        if (sqrt(sumSq / near) > MAX_FLAT_DEVIATION) return false

        val backgroundShare = near.toFloat() / total
        if (backgroundShare >= MIN_FLAT_SHARE && !swallowsBox) return true
        if (backgroundShare < MIN_BACKGROUND_SHARE) return false

        return looksLikeInk(far)
    }

    /**
     * True when [far] — the pixels that are not background — form a distinct ink population rather
     * than a smear of intermediate values.
     *
     * Two conditions, because either alone is fooled. A high median can come from a dark artwork mass;
     * few borderline pixels can come from a region with almost no ink at all. Together they describe a
     * histogram with paper at one end, lettering at the other, and little in between.
     */
    private fun looksLikeInk(far: List<Int>): Boolean {
        if (far.size < MIN_INK_SAMPLES) return false

        val sorted = far.sorted()
        if (sorted[sorted.size / 2] < MIN_INK_MEDIAN) return false

        var borderline = 0
        for (d in sorted) if (d < MIN_INK_MEDIAN) borderline++
        return borderline.toFloat() / sorted.size <= MAX_BORDERLINE_SHARE
    }

    private fun largestComponent(
        mask: BooleanArray,
        width: Int,
        height: Int,
        searchArea: Rect,
    ): IntArray? {
        val visited = BooleanArray(mask.size)
        val stack = ArrayDeque<Int>()
        var best: IntArray? = null

        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) {
                val start = base + x
                if (!mask[start] || visited[start]) continue

                val component = ArrayList<Int>()
                stack.addLast(start)
                visited[start] = true
                while (stack.isNotEmpty()) {
                    val index = stack.removeLast()
                    component.add(index)
                    val px = index % width
                    val py = index / width
                    if (px > searchArea.left) push(mask, visited, stack, index - 1)
                    if (px < searchArea.right - 1) push(mask, visited, stack, index + 1)
                    if (py > searchArea.top) push(mask, visited, stack, index - width)
                    if (py < searchArea.bottom - 1) push(mask, visited, stack, index + width)
                }
                if (best == null || component.size > best!!.size) best = component.toIntArray()
            }
        }
        return best
    }

    private fun push(mask: BooleanArray, visited: BooleanArray, stack: ArrayDeque<Int>, index: Int) {
        if (mask[index] && !visited[index]) {
            visited[index] = true
            stack.addLast(index)
        }
    }

    /**
     * Closes the lettering back into the interior by filling each row between its outermost interior
     * pixels — the scanline equivalent of filling a contour solid.
     *
     * The obvious alternative, flooding "outside" inwards from the border and treating whatever it
     * cannot reach as a hole, is wrong here and fails in a way that is easy to miss: a line of
     * lettering whose strokes happen to connect out to the border — bold or large type, or a bubble
     * that runs past the search area — is reached by that flood and therefore never filled. The
     * result is a bubble where some lines of the original are erased and others survive untouched,
     * sitting between lines of the translation.
     *
     * Filling by row has no such dependency on connectivity. It does assume each row of the bubble is
     * a single horizontal run, which holds for speech bubbles: they are convex or nearly so. A tail
     * hanging off the side is the one exception; it is narrow enough that the extra fill is hidden
     * under the outline.
     */
    /**
     * The drawn line around the bubble, as opposed to the lettering inside it.
     *
     * [fillHoles] repaints everything between the outermost interior pixels of a row, which is right
     * for lettering and wrong for the outline. On a bubble with a concave edge — a shout bubble whose
     * top dips inward, say — those rows have interior on both sides of the outline, so the span runs
     * straight through it and the bubble ends up drawn as disconnected fragments.
     *
     * The two are separated on the ink itself rather than on "everything that is not interior", which
     * matters: the page outside a concave edge is often the same paper white, and it merges with the
     * outline into one thick blob that no longer looks like a line at all.
     *
     * A component is structure when it is **thin** — an outline encloses far more of its bounding box
     * than it fills — and either reaches across a good part of the bubble or runs off the edge of the
     * search area. Measured on the bubble this was written for, the three outline pieces filled
     * 0.08–0.26 of their boxes while every lettering component filled 0.52–0.82.
     *
     * Shape alone is not enough, though, and the failure is severe when it goes wrong: **outlined
     * display type** — a title or a sound effect drawn as hollow letters — is also thin, also reaches
     * across the box, and its counters form the same kind of enclosed field a bubble interior does. On
     * the page that exposed this, protecting it left the original word sitting untouched with the
     * translation printed underneath. So [textLines] has the final say: anything OCR read as text is
     * lettering, whatever it looks like. With no OCR lines to go on nothing is protected at all, which
     * is the behaviour that predates this guard and errs toward erasing rather than preserving.
     */
    private fun structuralInk(
        pixels: IntArray,
        fillColor: Int,
        width: Int,
        searchArea: Rect,
        textLines: List<Rect>,
    ): BooleanArray {
        val structure = BooleanArray(pixels.size)
        if (textLines.isEmpty()) return structure
        val visited = BooleanArray(pixels.size)
        val stack = ArrayDeque<Int>()
        val component = ArrayList<Int>()
        val spanWidth = max(1, searchArea.width())
        val spanHeight = max(1, searchArea.height())

        fun isInk(index: Int) = distance(pixels[index], fillColor) >= INK_SEPARATION

        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) {
                val start = base + x
                if (visited[start] || !isInk(start)) continue

                component.clear()
                stack.addLast(start)
                visited[start] = true
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y

                while (stack.isNotEmpty()) {
                    val index = stack.removeLast()
                    component.add(index)
                    val px = index % width
                    val py = index / width
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py

                    if (px > searchArea.left) pushInk(::isInk, visited, stack, index - 1)
                    if (px < searchArea.right - 1) pushInk(::isInk, visited, stack, index + 1)
                    if (py > searchArea.top) pushInk(::isInk, visited, stack, index - width)
                    if (py < searchArea.bottom - 1) pushInk(::isInk, visited, stack, index + width)
                }

                if (component.size < MIN_STRUCTURE_PIXELS) continue
                val boxWidth = maxX - minX + 1
                val boxHeight = maxY - minY + 1
                if (component.size.toFloat() / max(1, boxWidth * boxHeight) > MAX_STRUCTURE_FILL) continue

                // OCR is stronger evidence than shape here. If a component reaches a recognised
                // text line, leave it to the glyph pass instead of classifying it as structural ink.
                // This deliberately protects a bubble outline/tail that touches the OCR rectangle:
                // once admitted into fillHoles, one long connected stroke can be painted away.
                val bounds = Rect(minX, minY, maxX + 1, maxY + 1)
                if (textLines.any { Rect.intersects(it, bounds) }) continue

                val span = max(boxWidth.toFloat() / spanWidth, boxHeight.toFloat() / spanHeight)
                val clipped = minX <= searchArea.left || minY <= searchArea.top ||
                    maxX >= searchArea.right - 1 || maxY >= searchArea.bottom - 1
                if (span < STRUCTURE_SPAN_RATIO && !clipped) continue

                for (index in component) structure[index] = true
            }
        }
        return structure
    }

    private inline fun pushInk(
        isInk: (Int) -> Boolean,
        visited: BooleanArray,
        stack: ArrayDeque<Int>,
        index: Int,
    ) {
        if (!visited[index] && isInk(index)) {
            visited[index] = true
            stack.addLast(index)
        }
    }

    private fun fillHoles(
        region: BooleanArray,
        width: Int,
        height: Int,
        searchArea: Rect,
        structure: BooleanArray,
    ) {
        val rows = searchArea.height()
        val left = IntArray(rows) { -1 }
        val right = IntArray(rows) { -1 }

        for (i in 0 until rows) {
            val base = (searchArea.top + i) * width
            for (x in searchArea.left until searchArea.right) {
                if (region[base + x]) {
                    if (left[i] < 0) left[i] = x
                    right[i] = x
                }
            }
        }

        val firstRow = left.indexOfFirst { it >= 0 }
        if (firstRow < 0) return
        val lastRow = left.indexOfLast { it >= 0 }

        // Rows with no interior pixels at all still belong to the bubble when they fall between rows
        // that do: a line of bold lettering can run the full width of the interior, leaving no
        // background on that row. Skipping such a row is what let whole lines of the original survive
        // in the middle of a translated bubble. Spanning them from their neighbours is what filling a
        // contour solid does implicitly.
        for (i in firstRow..lastRow) {
            if (left[i] >= 0) continue
            var above = i - 1
            while (above >= firstRow && left[above] < 0) above--
            var below = i + 1
            while (below <= lastRow && left[below] < 0) below++
            if (above < firstRow || below > lastRow) continue
            left[i] = min(left[above], left[below])
            right[i] = max(right[above], right[below])
        }

        for (i in firstRow..lastRow) {
            if (left[i] < 0) continue
            val base = (searchArea.top + i) * width
            // Skipping structural pixels is what keeps a concave bubble intact. Its top edge dips
            // below the interior's highest point, so those rows have interior on both sides of the
            // outline and the span runs straight through it — which used to repaint the outline in
            // paper colour and leave the bubble drawn as disconnected fragments.
            for (x in left[i]..right[i]) {
                val index = base + x
                if (!structure[index]) region[index] = true
            }
        }
    }

    private fun erode(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (!mask[base + x]) continue
                var keep = true
                var dy = -radius
                while (dy <= radius && keep) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) {
                        keep = false
                        break
                    }
                    var dx = -radius
                    while (dx <= radius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width || !mask[ny * width + nx]) {
                            keep = false
                            break
                        }
                        dx++
                    }
                    dy++
                }
                if (keep) out[base + x] = true
            }
        }
        return out
    }

    private fun boundsOf(mask: BooleanArray, width: Int): Rect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = i % width
            val y = i / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun distance(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        max(
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        ),
    )

    private inline fun forEachIn(area: Rect, width: Int, action: (Int) -> Unit) {
        for (y in area.top until area.bottom) {
            val base = y * width
            for (x in area.left until area.right) action(base + x)
        }
    }

    private const val MIN_SIDE = 12
    private const val CENTRE_INSET = 0.22f
    private const val INTERIOR_TOLERANCE = 46
    private const val INK_SEPARATION = 70
    private const val MIN_INK_SAMPLES = 24
    private const val MIN_INTERIOR_RATIO = 0.12f
    private const val MAX_INTERIOR_RATIO = 0.94f
    private const val MIN_FLAT_SHARE = 0.62f
    private const val MAX_FLAT_DEVIATION = 22.0
    private const val OUTLINE_CLEARANCE = 2

    /** Floor for the background below which nothing is flat-filled, however clean the ink looks. */
    private const val MIN_BACKGROUND_SHARE = 0.30f
    /** Colour distance from the paper that a pixel must clear to count as ink rather than shading. */
    private const val MIN_INK_MEDIAN = 110
    /** Share of non-background pixels allowed to sit in the gap between paper and ink. */
    private const val MAX_BORDERLINE_SHARE = 0.35f

    /** Fraction of the search area an ink component must reach before it counts as outline. */
    private const val STRUCTURE_SPAN_RATIO = 0.30f
    /** How sparsely it must fill its own box — the test that separates a drawn line from a letter. */
    private const val MAX_STRUCTURE_FILL = 0.35f
    private const val MIN_STRUCTURE_PIXELS = 40
}
