package mihon.feature.translation.render

import kotlin.math.max

/** Pure geometry for a renderer crop that contains accepted glyphs without escaping its source page. */
internal object RenderCropGuard {

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun bounds(
        detector: Bounds,
        ocrLines: List<Bounds>,
        detectorPad: Int,
        imageWidth: Int,
        pageTop: Int,
        pageBottom: Int,
    ): Bounds? {
        if (imageWidth <= 0 || pageBottom <= pageTop) return null

        var left = detector.left - detectorPad
        var top = detector.top - detectorPad
        var right = detector.right + detectorPad
        var bottom = detector.bottom + detectorPad

        if (ocrLines.isNotEmpty()) {
            // OCR rectangles hug the dark core and omit anti-aliased edge pixels. A small margin
            // includes that fringe while staying tied to positive lettering evidence.
            val tallestLine = ocrLines.maxOf { it.height.coerceAtLeast(1) }
            val margin = max(MIN_OCR_MARGIN, tallestLine / OCR_MARGIN_DIVISOR).coerceAtMost(MAX_OCR_MARGIN)
            left = minOf(left, ocrLines.minOf { it.left } - margin)
            top = minOf(top, ocrLines.minOf { it.top } - margin)
            right = maxOf(right, ocrLines.maxOf { it.right } + margin)
            bottom = maxOf(bottom, ocrLines.maxOf { it.bottom } + margin)
        }

        val clampedLeft = left.coerceAtLeast(0)
        val clampedTop = top.coerceAtLeast(pageTop)
        val clampedRight = right.coerceAtMost(imageWidth)
        val clampedBottom = bottom.coerceAtMost(pageBottom)
        return Bounds(clampedLeft, clampedTop, clampedRight, clampedBottom)
            .takeIf { it.width > 0 && it.height > 0 }
    }

    private const val MIN_OCR_MARGIN = 4
    private const val MAX_OCR_MARGIN = 16
    private const val OCR_MARGIN_DIVISOR = 3
}
