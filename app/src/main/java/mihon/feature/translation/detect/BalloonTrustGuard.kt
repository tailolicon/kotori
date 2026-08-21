package mihon.feature.translation.detect

import mihon.feature.translation.model.BubbleBox

/**
 * Decides whether a detector box may be believed to be one speech balloon.
 *
 * The answer governs two things at once, and separating them is what put a sentence across the
 * artwork: a box may be used as the region to letter into, *and* it may be used as evidence that
 * several blocks of lettering belong to the same utterance. The renderer sizes type to the lettering
 * it is handed rather than to the box, so a join made on a bad box is not saved by rejecting that
 * box afterwards — the merged lettering still spans whatever gulf lay between the blocks.
 */
internal object BalloonTrustGuard {

    /**
     * @param balloon the detector's box
     * @param claimed the lettering blocks it contains, in the same coordinate space
     */
    fun isBelievable(balloon: BubbleBox, claimed: List<BubbleBox>): Boolean {
        if (claimed.isEmpty()) return false
        val hull = claimed.reduce(::hullOf)
        val hullArea = hull.width.toLong() * hull.height
        if (hullArea <= 0) return false

        // A box far larger than everything inside it is a panel, not a balloon.
        val balloonArea = balloon.width.toLong() * balloon.height
        if (balloonArea > hullArea * MAX_BALLOON_TO_TEXT) return false

        // A box whose contents are strangers separated by open artwork is a panel too. Lettering
        // that really shares a balloon is stacked against itself, so its hull is barely larger than
        // the blocks; the detection that spanned two balloons scored 5.8x.
        if (claimed.size > 1) {
            val partsArea = claimed.sumOf { it.width.toLong() * it.height }
            if (partsArea > 0 && hullArea > partsArea * MAX_JOIN_SPREAD) return false
        }
        return true
    }

    /** Smallest box holding both — the footprint of lettering read as more than one block. */
    fun hullOf(a: BubbleBox, b: BubbleBox): BubbleBox = a.copy(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )

    /**
     * A detector box this many times larger than the lettering it holds is a panel, not a balloon.
     * Measured: a real oval is 2-5x the text; the chandelier false positive was ~20x.
     */
    private const val MAX_BALLOON_TO_TEXT = 8

    /**
     * How much larger the hull of several blocks may be than the blocks themselves.
     *
     * A balloon read as two or three blocks runs about 1.1-1.5x their combined area.
     */
    private const val MAX_JOIN_SPREAD = 3
}
