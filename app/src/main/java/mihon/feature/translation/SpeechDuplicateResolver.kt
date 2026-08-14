package mihon.feature.translation

/** Keeps the tightest OCR crop when the detector reports the same speech balloon twice. */
internal object SpeechDuplicateResolver {

    data class Candidate(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val isSpeech: Boolean,
        val text: String,
    )

    fun keepIndices(candidates: List<Candidate>): List<Int> {
        val drop = mutableSetOf<Int>()
        for (leftIndex in candidates.indices) {
            if (leftIndex in drop || !candidates[leftIndex].isSpeech) continue
            for (rightIndex in leftIndex + 1 until candidates.size) {
                if (rightIndex in drop || !candidates[rightIndex].isSpeech) continue
                val left = candidates[leftIndex]
                val right = candidates[rightIndex]
                if (!sameSpeechArea(left, right)) continue
                if (textSimilarity(left.text, right.text) < MIN_TEXT_SIMILARITY) continue

                val preferred = preferredIndex(leftIndex, left, rightIndex, right)
                if (preferred == leftIndex) drop += rightIndex else drop += leftIndex
                if (leftIndex in drop) break
            }
        }
        return candidates.indices.filterNot(drop::contains)
    }

    private fun sameSpeechArea(left: Candidate, right: Candidate): Boolean {
        if (overlapOfSmaller(left, right) >= MIN_OVERLAP) return true
        val horizontalOverlap = minOf(left.right, right.right) - maxOf(left.left, right.left)
        val narrower = minOf(left.right - left.left, right.right - right.left).coerceAtLeast(1)
        if (horizontalOverlap.toFloat() / narrower < MIN_HORIZONTAL_OVERLAP) return false
        val verticalGap = when {
            left.bottom < right.top -> right.top - left.bottom
            right.bottom < left.top -> left.top - right.bottom
            else -> 0
        }
        val taller = maxOf(left.bottom - left.top, right.bottom - right.top).coerceAtLeast(1)
        return verticalGap * MAX_VERTICAL_GAP_DENOMINATOR <= taller
    }

    private fun preferredIndex(
        leftIndex: Int,
        left: Candidate,
        rightIndex: Int,
        right: Candidate,
    ): Int {
        val leftQuality = textQuality(left.text)
        val rightQuality = textQuality(right.text)
        if (kotlin.math.abs(leftQuality - rightQuality) >= QUALITY_MARGIN) {
            return if (leftQuality > rightQuality) leftIndex else rightIndex
        }
        // Comparable readings belong to the same balloon. The broader crop is safer for erasing all
        // source glyphs; OCR-error penalties above still let a precise clean crop beat MIS5ION/G0.
        return if (area(left) >= area(right)) leftIndex else rightIndex
    }

    private fun textQuality(text: String): Int {
        val letters = text.count(Char::isLetter)
        val digits = text.count(Char::isDigit)
        return letters - digits * DIGIT_PENALTY
    }

    private fun textSimilarity(left: String, right: String): Float {
        val a = normalize(left)
        val b = normalize(right)
        if (a.length < MIN_TEXT_LENGTH || b.length < MIN_TEXT_LENGTH) return 0f
        val longest = maxOf(a.length, b.length)
        return 1f - editDistance(a, b).toFloat() / longest
    }

    private fun normalize(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun overlapOfSmaller(a: Candidate, b: Candidate): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val smaller = minOf(area(a), area(b))
        if (smaller <= 0L) return 0f
        return ((right - left).toLong() * (bottom - top)).toFloat() / smaller
    }

    private fun area(candidate: Candidate): Long =
        (candidate.right - candidate.left).coerceAtLeast(0).toLong() *
            (candidate.bottom - candidate.top).coerceAtLeast(0)

    private const val MIN_OVERLAP = 0.75f
    private const val MIN_HORIZONTAL_OVERLAP = 0.80f
    private const val MAX_VERTICAL_GAP_DENOMINATOR = 2
    private const val MIN_TEXT_SIMILARITY = 0.72f
    private const val MIN_TEXT_LENGTH = 5
    private const val QUALITY_MARGIN = 2
    private const val DIGIT_PENALTY = 4
}
