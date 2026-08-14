package mihon.feature.translation

/** Resolves whole-page OCR fallbacks against the speech crops they overlap. */
internal object FallbackTextBlockResolver {

    data class Candidate(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val isSpeech: Boolean,
        val isFallback: Boolean,
        val text: String,
    )

    fun keepIndices(candidates: List<Candidate>): List<Int> {
        val drop = mutableSetOf<Int>()
        candidates.indices.filter { candidates[it].isFallback }.forEach { fallbackIndex ->
            val fallback = candidates[fallbackIndex]
            val speechIndex = candidates.indices
                .filter { candidates[it].isSpeech && overlapOfSmaller(candidates[it], fallback) >= MIN_OVERLAP }
                .maxByOrNull { overlapOfSmaller(candidates[it], fallback) }
            if (speechIndex == null) return@forEach

            val speechCharacters = candidates[speechIndex].text.count(Char::isLetterOrDigit)
            val fallbackCharacters = fallback.text.count(Char::isLetterOrDigit)
            // A tight detector crop can read a perfectly real fragment (and therefore clear the
            // old six-character threshold) while still losing most of the sentence.  The whole-page
            // OCR block is the better source whenever it contains substantially more lettering.
            // Keep the detector geometry when both readings have comparable coverage; that geometry
            // follows the balloon better and is safer for repainting.
            val fallbackIsMoreComplete =
                fallbackCharacters >= speechCharacters + MIN_EXTRA_FALLBACK_CHARS &&
                    fallbackCharacters * COVERAGE_DENOMINATOR >= speechCharacters * COVERAGE_NUMERATOR
            val speechReadable = speechCharacters >= MIN_SPEECH_CHARS && !fallbackIsMoreComplete
            if (speechReadable) {
                drop += fallbackIndex
            } else {
                drop += speechIndex
            }
        }
        return candidates.indices.filterNot(drop::contains)
    }

    private fun overlapOfSmaller(a: Candidate, b: Candidate): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val areaA = (a.right - a.left).toLong() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toLong() * (b.bottom - b.top)
        val smaller = minOf(areaA, areaB)
        if (smaller <= 0L) return 0f
        return ((right - left).toLong() * (bottom - top)).toFloat() / smaller
    }

    private const val MIN_OVERLAP = 0.50f
    private const val MIN_SPEECH_CHARS = 6
    private const val MIN_EXTRA_FALLBACK_CHARS = 8
    private const val COVERAGE_NUMERATOR = 4
    private const val COVERAGE_DENOMINATOR = 3
}
