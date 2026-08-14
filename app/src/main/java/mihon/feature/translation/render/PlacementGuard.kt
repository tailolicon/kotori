package mihon.feature.translation.render

import kotlin.math.max

/** Pure geometry guard that prevents an expanded flood from moving text away from its OCR evidence. */
internal object PlacementGuard {

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun union(other: Bounds): Bounds = Bounds(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom),
        )
    }

    /** Allowed speech placement around the strongest geometry supplied by the caller. */
    fun allowed(evidence: List<Bounds>, cropWidth: Int, cropHeight: Int): Bounds? {
        if (evidence.isEmpty() || cropWidth <= 0 || cropHeight <= 0) return null
        val union = evidence.drop(1).fold(evidence.first(), Bounds::union)
        val longSide = max(union.width, union.height).coerceAtLeast(1)
        val padX = max(MIN_PAD, (longSide * PAD_X_RATIO).toInt()).coerceAtMost(MAX_PAD_X)
        val padY = max(MIN_PAD, (longSide * PAD_Y_RATIO).toInt()).coerceAtMost(MAX_PAD_Y)
        return Bounds(
            left = (union.left - padX).coerceAtLeast(0),
            top = (union.top - padY).coerceAtLeast(0),
            right = (union.right + padX).coerceAtMost(cropWidth),
            bottom = (union.bottom + padY).coerceAtMost(cropHeight),
        )
    }

    /** Uses OCR geometry when present; an oversized detector box is only a no-OCR fallback. */
    fun allowedFromOcrOrDetector(
        ocrEvidence: List<Bounds>,
        detectorEvidence: Bounds,
        cropWidth: Int,
        cropHeight: Int,
    ): Bounds? = allowed(
        evidence = ocrEvidence.ifEmpty { listOf(detectorEvidence) },
        cropWidth = cropWidth,
        cropHeight = cropHeight,
    )

    private const val PAD_X_RATIO = 0.35f
    private const val PAD_Y_RATIO = 0.65f
    private const val MIN_PAD = 8
    private const val MAX_PAD_X = 160
    private const val MAX_PAD_Y = 240
}
