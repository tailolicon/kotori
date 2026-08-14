package mihon.feature.translation.detect

/** Keeps very weak detector proposals only when they have the shape of a tiny line of lettering. */
internal object DetectorProposalPolicy {

    fun shouldKeep(confidence: Float, width: Float, height: Float): Boolean {
        if (confidence >= REGULAR_PROPOSAL_CONFIDENCE) return true
        if (confidence < MIN_COMPACT_CONFIDENCE || width <= 0f || height <= 0f) return false
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        return longSide <= MAX_LONG_SIDE && shortSide <= MAX_SHORT_SIDE &&
            longSide >= shortSide * MIN_LINE_ASPECT
    }

    private const val REGULAR_PROPOSAL_CONFIDENCE = 0.10f
    private const val MIN_COMPACT_CONFIDENCE = 0.055f
    private const val MAX_LONG_SIDE = 180f
    private const val MAX_SHORT_SIDE = 64f
    private const val MIN_LINE_ASPECT = 1.5f
}
