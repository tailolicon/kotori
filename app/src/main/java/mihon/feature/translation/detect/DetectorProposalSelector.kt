package mihon.feature.translation.detect

/** Bounds detector fan-out while preserving one weak compact lettering candidate per page cell. */
internal object DetectorProposalSelector {

    data class Proposal(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )

    fun keepIndices(proposals: List<Proposal>, pageWidth: Int, pageHeight: Int): List<Int> {
        if (proposals.isEmpty()) return emptyList()
        val ranked = proposals.indices.sortedByDescending { proposals[it].confidence }
        val regular = ranked
            .filter { proposals[it].confidence >= REGULAR_CONFIDENCE }
            .take(MAX_REGULAR_PROPOSALS)

        // A global confidence cap deleted isolated words such as YOU near the bottom edge because
        // dozens of slightly stronger sound-effect proposals occurred above it. Spatial reservation
        // keeps that evidence while allowing at most one expensive weak OCR attempt per cell.
        val weakByCell = linkedMapOf<Int, Int>()
        ranked.asSequence()
            .filter { proposals[it].confidence < REGULAR_CONFIDENCE }
            .forEach { index ->
                val proposal = proposals[index]
                val centerX = (proposal.left + proposal.right) / 2f
                val centerY = (proposal.top + proposal.bottom) / 2f
                val column = (centerX * GRID_COLUMNS / pageWidth.coerceAtLeast(1))
                    .toInt().coerceIn(0, GRID_COLUMNS - 1)
                val row = (centerY * GRID_ROWS / pageHeight.coerceAtLeast(1))
                    .toInt().coerceIn(0, GRID_ROWS - 1)
                weakByCell.putIfAbsent(row * GRID_COLUMNS + column, index)
            }

        return (regular + weakByCell.values).distinct().sortedByDescending { proposals[it].confidence }
    }

    private const val REGULAR_CONFIDENCE = 0.10f
    private const val MAX_REGULAR_PROPOSALS = 28
    private const val GRID_COLUMNS = 6
    private const val GRID_ROWS = 8
}
