package mihon.feature.translation.ocr

import kotlin.math.max

/** Pure geometry used to keep an expanded OCR crop inside one physical speech balloon. */
internal object SpeechLineSelector {

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Returns the candidate indices belonging to the vertically connected line cluster anchored in
     * [speechBox]. Expansion is deliberately based on line spacing, not box height: a tall detector
     * crop may see the next balloon in the same column, but two lines in one balloon remain close.
     */
    fun select(candidates: List<Bounds>, speechBox: Bounds): List<Int> {
        val inLane = candidates.indices.filter { horizontalShare(candidates[it], speechBox) >= MIN_HORIZONTAL_SHARE }
        if (inLane.isEmpty()) return emptyList()

        val heights = inLane.map { candidates[it].height.coerceAtLeast(1) }.sorted()
        val medianHeight = heights[heights.size / 2]
        val maxLineGap = max(MIN_LINE_GAP, (medianHeight * MAX_LINE_GAP_HEIGHTS).toInt())
        val ordered = inLane.sortedWith(compareBy({ candidates[it].top }, { candidates[it].left }))

        val components = mutableListOf<MutableList<Int>>()
        for (index in ordered) {
            val current = candidates[index]
            val component = components.lastOrNull()
            val previous = component?.lastOrNull()?.let(candidates::get)
            val connected = previous != null &&
                current.top - previous.bottom <= maxLineGap &&
                horizontalShare(current, previous) >= MIN_ADJACENT_SHARE
            if (connected) {
                component += index
            } else {
                components += mutableListOf(index)
            }
        }

        val anchored = components.filter { component ->
            component.any { verticalOverlap(candidates[it], speechBox) > 0 }
        }
        val selected = if (anchored.isNotEmpty()) {
            anchored.maxWithOrNull(
                compareBy<List<Int>>(
                    { component -> component.count { verticalOverlap(candidates[it], speechBox) > 0 } },
                    { component -> component.sumOf { verticalOverlap(candidates[it], speechBox) } },
                ),
            )
        } else {
            // A detector box can land in the whitespace between two tightly set lines. Keep only the
            // nearest cluster, and only when it is close enough to be credible speech from this box.
            components.minByOrNull { component -> component.minOf { verticalGap(candidates[it], speechBox) } }
                ?.takeIf { component -> component.minOf { verticalGap(candidates[it], speechBox) } <= maxLineGap }
        }
        return selected.orEmpty().sortedWith(compareBy({ candidates[it].top }, { candidates[it].left }))
    }

    /**
     * Vertical crop room for a detector box that may cover only one short line of a tall balloon.
     * Width is the useful signal in that failure mode; using height alone made the two lines above a
     * 96x35 detection physically unavailable to OCR.
     */
    fun verticalCropPadding(boxWidth: Int, boxHeight: Int): Int =
        max(
            (boxHeight * HEIGHT_PAD_RATIO).toInt(),
            (boxWidth * WIDTH_PAD_RATIO).toInt(),
        ).coerceIn(MIN_CROP_PAD, MAX_CROP_PAD)

    private fun horizontalShare(a: Bounds, b: Bounds): Float {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val narrower = minOf(a.width, b.width).coerceAtLeast(1)
        return overlap.coerceAtLeast(0).toFloat() / narrower
    }

    private fun verticalOverlap(a: Bounds, b: Bounds): Int =
        (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)

    private fun verticalGap(a: Bounds, b: Bounds): Int = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0
    }

    private const val MIN_HORIZONTAL_SHARE = 0.55f
    private const val MIN_ADJACENT_SHARE = 0.25f
    private const val MIN_LINE_GAP = 8
    private const val MAX_LINE_GAP_HEIGHTS = 1.8f
    private const val HEIGHT_PAD_RATIO = 0.85f
    private const val WIDTH_PAD_RATIO = 0.65f
    private const val MIN_CROP_PAD = 12
    private const val MAX_CROP_PAD = 180
}
