package mihon.feature.translation.render

/** Prevents a nearby, independent speech balloon being absorbed into a caption plan. */
internal object CaptionFragmentGuard {

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun shouldAbsorb(
        speech: Bounds,
        textBlock: Bounds,
        textSlot: Bounds,
    ): Boolean {
        // Proximity alone is not ownership. Manga routinely places two different balloons on the
        // same baseline with only a narrow gutter between them. A detector fragment may be absorbed
        // only when it actually reaches the caption's prepared lettering slot.
        if (!intersects(speech, textSlot)) return false

        val vertical = minOf(speech.bottom, textBlock.bottom) - maxOf(speech.top, textBlock.top)
        val rowOverlap = vertical.coerceAtLeast(0).toFloat() /
            minOf(speech.height, textBlock.height).coerceAtLeast(1)
        if (rowOverlap < MIN_ROW_OVERLAP) return false

        val gap = when {
            speech.right < textBlock.left -> textBlock.left - speech.right
            textBlock.right < speech.left -> speech.left - textBlock.right
            else -> 0
        }
        return gap <= minOf(speech.height, textBlock.height) * MAX_GAP_HEIGHTS
    }

    private fun intersects(a: Bounds, b: Bounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private const val MIN_ROW_OVERLAP = 0.45f
    private const val MAX_GAP_HEIGHTS = 2
}
