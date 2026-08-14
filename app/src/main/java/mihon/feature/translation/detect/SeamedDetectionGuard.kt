package mihon.feature.translation.detect

/** Chooses the only continuous-strip detections that may supplement page-aligned detection. */
internal object SeamedDetectionGuard {

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /**
     * A continuous detection is useful only for a box genuinely crossing a source seam. If a
     * page-aligned pass already found a box substantially inside it, prefer that stable local box;
     * arbitrary overlapping strip windows are otherwise able to merge a balloon with half a panel.
     */
    fun supplementalIndices(
        continuous: List<Bounds>,
        pageAligned: List<Bounds>,
        seams: IntArray,
    ): List<Int> = continuous.indices.filter { index ->
        val candidate = continuous[index]
        val duplicatesLocal = pageAligned.any { local ->
            shareOfSmallerIntersection(local, candidate) >= LOCAL_CONTAINMENT
        }
        // Unsupported page-local detections recover small balloons that the whole-page pass missed.
        // Unsupported cross-seam detections recover a balloon split between two manhwa source slices.
        // In both cases an overlapping page-aligned box wins, preventing an arbitrary strip window
        // from replacing stable manga geometry with a panel-sized false positive.
        !duplicatesLocal &&
            (insideOneSegment(candidate, seams) || seams.any { seam -> seam > candidate.top && seam < candidate.bottom })
    }

    private fun shareOfSmallerIntersection(a: Bounds, b: Bounds): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val areaA = (a.right - a.left).toLong() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toLong() * (b.bottom - b.top)
        val smaller = minOf(areaA, areaB)
        if (smaller <= 0L) return 1f
        return ((right - left).toLong() * (bottom - top)).toFloat() / smaller
    }

    private fun insideOneSegment(candidate: Bounds, seams: IntArray): Boolean =
        seams.none { seam -> seam > candidate.top && seam < candidate.bottom }

    private const val LOCAL_CONTAINMENT = 0.65f
}
