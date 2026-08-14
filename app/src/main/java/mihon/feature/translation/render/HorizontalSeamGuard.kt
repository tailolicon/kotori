package mihon.feature.translation.render

/** Keeps independent source pages isolated while still allowing a genuinely split webtoon balloon. */
internal object HorizontalSeamGuard {

    data class Span(val top: Int, val bottom: Int)

    /**
     * Returns the source-page segment containing the detector [evidence], or `null` when the detector
     * itself crosses a seam. A crossing is positive evidence of a manhwa balloon split between source
     * slices, so it must remain continuous; OCR padding or flood-fill alone may never create it.
     */
    fun segment(evidence: Span, seams: IntArray, totalHeight: Int): Span? {
        if (seams.isEmpty() || totalHeight <= 0) return Span(0, totalHeight)
        if (seams.any { it > evidence.top && it < evidence.bottom }) return null

        val center = ((evidence.top.toLong() + evidence.bottom) / 2L).toInt().coerceIn(0, totalHeight)
        val top = seams.lastOrNull { it <= center } ?: 0
        val bottom = seams.firstOrNull { it > center } ?: totalHeight
        return Span(top, bottom)
    }
}
