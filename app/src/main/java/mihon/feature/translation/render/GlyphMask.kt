package mihon.feature.translation.render

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the pixels that belong to *lettering* inside a bubble region, and the pixels that belong to
 * the *drawing* and must be left alone.
 *
 * Why not just fill the detector's box with one flat colour: that erases the bubble outline, flattens
 * screentones and gradients, and on colour pages leaves an obvious rectangle. Masking only the glyph
 * strokes and reconstructing what was behind them keeps every other pixel as the artist drew it.
 *
 * Three decisions make this work on the cases a simple threshold fails:
 *
 *  1. **Stroke thickness is measured, not guessed.** Deriving it from the bubble's size gives a huge
 *     radius for a large bubble holding fine lettering, which merges every glyph into one blob.
 *     A cheap probe pass measures it from the ink itself via area/perimeter.
 *  2. **Structural ink is identified and protected.** Bubble outlines, tails and panel borders are
 *     recognised geometrically and excluded from the maskable area, so the mask can never eat them —
 *     including on spiky or hand-drawn bubbles whose outline breaks into many separate arcs.
 *  3. **Polarity is decided by structure, not energy.** Summing residuals picks the wrong polarity
 *     whenever a small light bubble sits on a dark field: the background dominates the sum and the
 *     whole bubble is treated as one giant glyph. Scoring by the *area of compact components* is
 *     robust, and ignores screentone dots and grain that would otherwise outvote real text.
 *
 * All constants are shared with the NumPy verification harness used to tune them against real pages.
 */
internal object GlyphMask {

    /**
     * @param mask dilated glyph mask — the pixels to reconstruct
     * @param core undilated glyph mask — sample the ink colour from this, not from [mask], whose
     *   edges have already grown into the paper
     * @param blocked structural ink plus clearance; nothing here may be masked or written over
     * @param lightOnDark true when glyphs are brighter than their surroundings
     */
    class Result(
        val mask: BooleanArray,
        val core: BooleanArray,
        val blocked: BooleanArray,
        val covered: Int,
        val lightOnDark: Boolean,
        val strokeRadius: Int,
    )

    fun detect(pixels: IntArray, width: Int, height: Int, searchArea: Rect): Result {
        val size = width * height
        val luminance = FloatArray(size)
        for (i in 0 until size) {
            val p = pixels[i]
            luminance[i] = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
        }

        // ── Probe: measure stroke thickness ──────────────────────────────────────────────────────
        // Only the scale is taken from this pass. Its polarity call is unreliable at such a small
        // radius — the thin white gaps between letters can look more like "ink" than the letters do —
        // but stroke width comes out much the same either way, and that is all it is used for.
        // The paper colour decides polarity when it can be established; see [residualFor].
        val paperColor = BubbleFill.dominantBackground(pixels, width, searchArea)

        val probe = residualFor(luminance, pixels, paperColor, width, height, searchArea, PROBE_MORPH_RADIUS)
        val probeInk = threshold(probe.residual, searchArea, width, height, PROBE_RESIDUAL)
        val strokeRadius = measureStrokeRadius(probeInk, width, height)

        // ── Full-scale pass: this is the polarity everything downstream must agree on ────────────
        val morphRadius = (strokeRadius * 2 * MORPH_RADIUS_FACTOR).roundToInt().coerceIn(2, MAX_MORPH_RADIUS)
        val full = residualFor(luminance, pixels, paperColor, width, height, searchArea, morphRadius)

        // Structural ink is derived from *this* residual rather than the probe's. Deriving it from the
        // probe let the two passes disagree about which side was ink: the probe would hand over a map
        // of the white gaps, no bubble outline could be found in it, and the outline was then left
        // unprotected and erased along with the lettering.
        val inkAtWorkingScale = threshold(full.residual, searchArea, width, height, PROBE_RESIDUAL)
        val structural = structuralInk(inkAtWorkingScale, width, height, searchArea, strokeRadius)
        val blocked = dilate(structural, width, height, strokeRadius + STRUCTURAL_CLEARANCE)

        val allowed = BooleanArray(size)
        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) {
                val index = base + x
                allowed[index] = !blocked[index]
            }
        }

        var core = buildMask(full.residual, allowed, width, height, searchArea)
        var lightOnDark = !full.darkInk

        val allowedArea = max(1, countTrue(allowed))
        if (countTrue(core) > allowedArea * MAX_COVERAGE_RATIO) {
            // Runaway coverage means the polarity call was wrong for this bubble specifically.
            val inverted = if (full.darkInk) full.opposite else full.primary
            val alternate = buildMask(inverted, allowed, width, height, searchArea)
            if (countTrue(alternate) < countTrue(core)) {
                core = alternate
                lightOnDark = full.darkInk
            }
        }

        val erasable = withGlyphOutlines(full, core, allowed, lightOnDark, width, height, searchArea, strokeRadius)

        val dilateRadius = max(MIN_DILATE_RADIUS, (strokeRadius * DILATE_FACTOR).roundToInt())
        val mask = dilate(erasable, width, height, dilateRadius).also { grown ->
            // Growth must still respect structural ink, or a glyph touching the outline drags the
            // outline into the mask.
            for (i in grown.indices) if (blocked[i]) grown[i] = false
        }

        return Result(
            mask = mask,
            core = core,
            blocked = blocked,
            covered = countTrue(mask),
            lightOnDark = lightOnDark,
            strokeRadius = strokeRadius,
        )
    }

    /**
     * Adds each glyph's contrasting outline to the set of pixels to erase.
     *
     * Display lettering is very often drawn twice: a light core with a dark rim, or the reverse, so
     * that it stays legible over artwork. Only one of those is ink under the chosen polarity, so
     * masking the core alone leaves the rim behind — and a rim is a complete outline of every letter,
     * which means the entire original paragraph survives inpainting as a perfectly legible ghost with
     * the translation printed over it. On the narration this was measured against, three quarters of
     * the opposite polarity's ink lay within two pixels of a core.
     *
     * Proximity to a core is what keeps this safe. Artwork of the opposite polarity elsewhere in the
     * region is untouched, and where the lettering has no rim — ordinary dark text on paper — the only
     * thing gathered is the thin light gap between strokes, which reconstructs to the paper colour it
     * already was.
     */
    private fun withGlyphOutlines(
        full: Residuals,
        core: BooleanArray,
        allowed: BooleanArray,
        lightOnDark: Boolean,
        width: Int,
        height: Int,
        searchArea: Rect,
        strokeRadius: Int,
    ): BooleanArray {
        // The polarity that is *not* ink is where a rim would show up.
        val rimResidual = if (lightOnDark) full.primary else full.opposite
        val rimCandidates = threshold(rimResidual, searchArea, width, height, PROBE_RESIDUAL)
        val nearCore = dilate(core, width, height, max(MIN_RIM_RADIUS, strokeRadius + 1))

        val erasable = core.copyOf()
        for (i in erasable.indices) {
            if (erasable[i] || !allowed[i] || !nearCore[i] || !rimCandidates[i]) continue
            erasable[i] = true
        }
        return erasable
    }

    // ── Polarity and residuals ──────────────────────────────────────────────────────────────────

    private class Residuals(
        val residual: FloatArray,
        val primary: FloatArray,
        val opposite: FloatArray,
        val darkInk: Boolean,
    )

    /**
     * Computes the ink residual against a local morphological background, choosing the polarity whose
     * thresholded result looks most like lettering.
     */
    private fun residualFor(
        luminance: FloatArray,
        pixels: IntArray,
        paperColor: Int?,
        width: Int,
        height: Int,
        searchArea: Rect,
        radius: Int,
    ): Residuals {
        // Closing removes thin dark features, so its residual reveals dark ink; opening does the
        // mirror for light ink.
        val closing = erode(dilate(luminance, width, height, radius), width, height, radius)
        val opening = dilate(erode(luminance, width, height, radius), width, height, radius)

        val darkResidual = FloatArray(luminance.size)
        val lightResidual = FloatArray(luminance.size)
        for (i in luminance.indices) {
            darkResidual[i] = max(0f, closing[i] - luminance[i])
            lightResidual[i] = max(0f, luminance[i] - opening[i])
        }

        val darkCoverage = coverageIn(darkResidual, width, searchArea)
        val lightCoverage = coverageIn(lightResidual, width, searchArea)

        // Ink is whichever polarity marks the pixels that differ most from the paper. That is the
        // physical definition of ink, it holds for dark-on-light and light-on-dark alike, and it does
        // not care how much of the region the lettering happens to occupy.
        //
        // Occupancy is what the previous rule — "the polarity marking less is the ink" — depended on,
        // and it inverts on display type: heavy lettering covers more of its own box than the gaps
        // between the letters do, so the gaps were taken for ink, inpainted, and the surrounding
        // lettering was dragged across them into horizontal smears while the replacement text was
        // drawn in the paper's own colour. Measured on the banners that failed, occupancy split
        // 0.45/0.40 while paper distance split 191/57 — the same call, made with a usable margin.
        val separation = paperColor?.let {
            distanceFromPaper(darkResidual, pixels, it, width, searchArea) to
                distanceFromPaper(lightResidual, pixels, it, width, searchArea)
        }

        val darkInk = when {
            // A polarity that marks almost nothing found no ink at all rather than "very little".
            darkCoverage < MIN_INK_COVERAGE -> false
            lightCoverage < MIN_INK_COVERAGE -> true
            separation != null && separation.first != separation.second ->
                separation.first > separation.second
            // No usable paper colour: fall back to occupancy, then to which looks more like lettering.
            darkCoverage != lightCoverage -> darkCoverage < lightCoverage
            else -> glyphLikeness(darkResidual, width, height, searchArea) >=
                glyphLikeness(lightResidual, width, height, searchArea)
        }

        return Residuals(
            residual = if (darkInk) darkResidual else lightResidual,
            primary = darkResidual,
            opposite = lightResidual,
            darkInk = darkInk,
        )
    }

    /**
     * Total area of compact, glyph-sized components in a thresholded residual.
     *
     * Scoring by area rather than component count is deliberate: screentone dots and film grain
     * produce hundreds of components against a dozen letters, and would win a popularity contest.
     * Their combined area is negligible next to the text's.
     */
    private fun glyphLikeness(residual: FloatArray, width: Int, height: Int, searchArea: Rect): Long {
        val mask = threshold(residual, searchArea, width, height, PROBE_RESIDUAL)
        val area = max(1, searchArea.width() * searchArea.height())
        val minSize = max(MIN_COMPONENT_PIXELS, (area * MIN_GLYPH_AREA_RATIO).toInt())
        val maxSize = (area * MAX_GLYPH_AREA_RATIO).toInt()

        var score = 0L
        var largest = 0
        forEachComponent(mask, width, height, searchArea) { pixels, bounds ->
            val count = pixels.size
            if (count > largest) largest = count
            if (count < minSize || count > maxSize) return@forEachComponent
            val h = bounds.height()
            val w = bounds.width()
            val aspect = max(h, w).toFloat() / max(1, min(h, w))
            val fill = count.toFloat() / max(1, h * w)
            // A letter is roughly as tall as it is wide and mostly fills its own box; hatching,
            // speed lines and rain are neither.
            if (aspect > MAX_GLYPH_ASPECT || fill < MIN_GLYPH_FILL) return@forEachComponent
            score += count
        }
        // One component swallowing the box is decisive evidence against this polarity.
        if (largest > area * MAX_GLYPH_AREA_RATIO) score -= largest
        return score
    }

    // ── Mask construction ───────────────────────────────────────────────────────────────────────

    private fun buildMask(
        residual: FloatArray,
        allowed: BooleanArray,
        width: Int,
        height: Int,
        searchArea: Rect,
    ): BooleanArray {
        var peak = 0f
        for (i in allowed.indices) {
            if (allowed[i] && residual[i] > peak) peak = residual[i]
        }
        val level = max(MIN_RESIDUAL, peak * RESIDUAL_RATIO)

        val mask = BooleanArray(residual.size)
        for (i in residual.indices) {
            if (allowed[i] && residual[i] >= level) mask[i] = true
        }

        // Recover anti-aliased stroke edges: a pixel just under threshold that touches a masked pixel
        // is stroke fringe. Leaving it behind is exactly what produces a ghost of the old lettering.
        val fringeLevel = level * FRINGE_RATIO
        val neighbours = dilate(mask, width, height, 1)
        for (i in residual.indices) {
            if (!mask[i] && allowed[i] && neighbours[i] && residual[i] >= fringeLevel) mask[i] = true
        }

        // Drop specks: screentone dots and paper grain add blur without removing any lettering.
        forEachComponent(mask, width, height, searchArea) { pixels, _ ->
            if (pixels.size < MIN_COMPONENT_PIXELS) {
                for (index in pixels) mask[index] = false
            }
        }
        return mask
    }

    /**
     * Ink belonging to the drawing rather than to lettering.
     *
     * Two independent tests, each requiring thinness:
     *  * reach — an arc that runs across a large share of the bubble in either axis; no single glyph
     *    does, because neighbouring letters are not connected to each other
     *  * absolute length — any thin run longer than [ART_SPAN_STROKES] stroke widths is a drawn line.
     *    This catches panel borders and speed lines inside a loosely detected box, where the
     *    box-relative test would not fire.
     *
     * Requiring thinness in both keeps a large stylised character — tall, but compact — out of the
     * structural set so it still gets translated.
     */
    private fun structuralInk(
        ink: BooleanArray,
        width: Int,
        height: Int,
        searchArea: Rect,
        strokeRadius: Int,
    ): BooleanArray {
        val reachHeight = searchArea.height() * OUTLINE_REACH_RATIO
        val reachWidth = searchArea.width() * OUTLINE_REACH_RATIO
        val strokeWidth = max(2, strokeRadius * 2)
        val artSpan = strokeWidth * ART_SPAN_STROKES

        val structural = BooleanArray(ink.size)
        forEachComponent(ink, width, height, searchArea) { pixels, bounds ->
            val h = bounds.height()
            val w = bounds.width()
            val reachesBox = h >= reachHeight || w >= reachWidth
            val runsLong = max(h, w) >= artSpan
            if (!reachesBox && !runsLong) return@forEachComponent
            if (pixels.size.toFloat() / max(1, h * w) > MAX_OUTLINE_FILL_RATIO) return@forEachComponent
            for (index in pixels) structural[index] = true
        }
        return structural
    }

    /**
     * Stroke half-width from the ink's area-to-perimeter ratio.
     *
     * For a stroke of width W and length L the area is about W·L and the perimeter about 2L, so
     * area/perimeter ≈ W/2 — independent of how much text there is or how large the bubble is.
     */
    private fun measureStrokeRadius(ink: BooleanArray, width: Int, height: Int): Int {
        var area = 0
        for (value in ink) if (value) area++
        if (area == 0) return DEFAULT_STROKE_RADIUS

        val interior = erode(ink, width, height, 1)
        var boundary = 0
        for (i in ink.indices) if (ink[i] && !interior[i]) boundary++
        if (boundary == 0) return DEFAULT_STROKE_RADIUS

        return (area.toFloat() / boundary).roundToInt().coerceIn(1, MAX_STROKE_RADIUS)
    }

    // ── Primitives ──────────────────────────────────────────────────────────────────────────────

    private fun threshold(
        residual: FloatArray,
        searchArea: Rect,
        width: Int,
        height: Int,
        level: Float,
    ): BooleanArray {
        val mask = BooleanArray(residual.size)
        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) {
                val index = base + x
                if (residual[index] >= level) mask[index] = true
            }
        }
        return mask
    }

    private fun countTrue(mask: BooleanArray): Int {
        var count = 0
        for (value in mask) if (value) count++
        return count
    }

    /**
     * Mean colour distance from [paperColor] over the pixels this residual marks.
     *
     * Returns 0 when the residual marks nothing, which loses to any polarity that marked something —
     * the desired outcome, since a polarity with no marked pixels has found no ink.
     */
    private fun distanceFromPaper(
        residual: FloatArray,
        pixels: IntArray,
        paperColor: Int,
        width: Int,
        searchArea: Rect,
    ): Float {
        val paperR = (paperColor shr 16) and 0xFF
        val paperG = (paperColor shr 8) and 0xFF
        val paperB = paperColor and 0xFF

        var total = 0L
        var count = 0
        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) {
                val index = base + x
                if (residual[index] < PROBE_RESIDUAL) continue
                val p = pixels[index]
                val d = max(
                    abs(((p shr 16) and 0xFF) - paperR),
                    max(abs(((p shr 8) and 0xFF) - paperG), abs((p and 0xFF) - paperB)),
                )
                total += d
                count++
            }
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    /** Fraction of [area] whose residual clears the probe threshold. */
    private fun coverageIn(residual: FloatArray, width: Int, area: Rect): Float {
        var marked = 0
        var total = 0
        for (y in area.top until area.bottom) {
            val base = y * width
            for (x in area.left until area.right) {
                if (residual[base + x] >= PROBE_RESIDUAL) marked++
                total++
            }
        }
        return if (total == 0) 0f else marked.toFloat() / total
    }

    /** 4-connected component walk over [mask], restricted to [searchArea]. */
    private inline fun forEachComponent(
        mask: BooleanArray,
        width: Int,
        height: Int,
        searchArea: Rect,
        action: (pixels: IntArray, bounds: Rect) -> Unit,
    ) {
        val visited = BooleanArray(mask.size)
        val stack = ArrayDeque<Int>()
        val component = ArrayList<Int>()

        for (y in searchArea.top until searchArea.bottom) {
            val base = y * width
            for (x in searchArea.left until searchArea.right) {
                val start = base + x
                if (!mask[start] || visited[start]) continue

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

                    if (px > 0) push(mask, visited, stack, index - 1)
                    if (px < width - 1) push(mask, visited, stack, index + 1)
                    if (py > 0) push(mask, visited, stack, index - width)
                    if (py < height - 1) push(mask, visited, stack, index + width)
                }

                action(component.toIntArray(), Rect(minX, minY, maxX + 1, maxY + 1))
            }
        }
    }

    private fun push(mask: BooleanArray, visited: BooleanArray, stack: ArrayDeque<Int>, index: Int) {
        if (mask[index] && !visited[index]) {
            visited[index] = true
            stack.addLast(index)
        }
    }

    // Separable rectangular morphology: a bubble-sized region costs microseconds instead of
    // milliseconds, which is what lets a whole page be processed without a visible stall.

    private fun dilate(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray =
        sweepVertical(sweepHorizontal(source, width, height, radius, true), width, height, radius, true)

    private fun erode(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray =
        sweepVertical(sweepHorizontal(source, width, height, radius, false), width, height, radius, false)

    private fun sweepHorizontal(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        wantMax: Boolean,
    ): FloatArray {
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var best = source[row + x]
                for (nx in max(0, x - radius)..min(width - 1, x + radius)) {
                    val value = source[row + nx]
                    if (if (wantMax) value > best else value < best) best = value
                }
                out[row + x] = best
            }
        }
        return out
    }

    private fun sweepVertical(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        wantMax: Boolean,
    ): FloatArray {
        val out = FloatArray(source.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var best = source[y * width + x]
                for (ny in max(0, y - radius)..min(height - 1, y + radius)) {
                    val value = source[ny * width + x]
                    if (if (wantMax) value > best else value < best) best = value
                }
                out[y * width + x] = best
            }
        }
        return out
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (!mask[base + x]) continue
                for (ny in max(0, y - radius)..min(height - 1, y + radius)) {
                    val rowBase = ny * width
                    for (nx in max(0, x - radius)..min(width - 1, x + radius)) {
                        out[rowBase + nx] = true
                    }
                }
            }
        }
        return out
    }

    private fun erode(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val inverted = BooleanArray(mask.size) { !mask[it] }
        val grown = dilate(inverted, width, height, radius)
        return BooleanArray(mask.size) { !grown[it] }
    }

    // ── Tunables, shared with the NumPy verification harness ────────────────────────────────────

    private const val PROBE_MORPH_RADIUS = 3
    private const val PROBE_RESIDUAL = 24f
    private const val DEFAULT_STROKE_RADIUS = 2
    private const val MAX_STROKE_RADIUS = 8

    private const val MORPH_RADIUS_FACTOR = 2.2f
    private const val MAX_MORPH_RADIUS = 14

    private const val RESIDUAL_RATIO = 0.30f
    private const val MIN_RESIDUAL = 16f
    private const val FRINGE_RATIO = 0.45f
    private const val DILATE_FACTOR = 1.0f
    private const val MIN_DILATE_RADIUS = 2
    /** How far from a glyph core a contrasting rim is still part of the lettering. */
    private const val MIN_RIM_RADIUS = 2
    private const val MIN_COMPONENT_PIXELS = 6
    private const val MAX_COVERAGE_RATIO = 0.55f

    /** Below this share of the search area a polarity has found no ink worth speaking of. */
    private const val MIN_INK_COVERAGE = 0.004f
    private const val MAX_GLYPH_AREA_RATIO = 0.30f
    private const val MIN_GLYPH_AREA_RATIO = 0.0004f
    private const val MAX_GLYPH_ASPECT = 6.0f
    private const val MIN_GLYPH_FILL = 0.22f

    private const val OUTLINE_REACH_RATIO = 0.45f
    private const val MAX_OUTLINE_FILL_RATIO = 0.35f
    private const val ART_SPAN_STROKES = 12
    private const val STRUCTURAL_CLEARANCE = 1
}
