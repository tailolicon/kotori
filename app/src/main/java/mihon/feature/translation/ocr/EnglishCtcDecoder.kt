package mihon.feature.translation.ocr

/** Greedy CTC decoder restricted to characters useful in English dialogue. */
internal object EnglishCtcDecoder {

    internal fun modelIndexOf(character: Char): Int =
        CHARACTER_SET.indexOf(character).takeIf { it >= 0 }?.plus(1) ?: -1

    fun decode(logits: Array<FloatArray>): String {
        val allowedIndices = ALLOWED_INDICES
        val result = StringBuilder()
        var previous = -1
        logits.forEach { timestep ->
            var bestIndex = 0
            var bestScore = timestep.getOrElse(0) { Float.NEGATIVE_INFINITY }
            allowedIndices.forEach { index ->
                val score = timestep.getOrElse(index) { Float.NEGATIVE_INFINITY }
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            if (bestIndex != BLANK_INDEX && bestIndex != previous) {
                CHARACTER_SET.getOrNull(bestIndex - 1)?.let(result::append)
            }
            previous = bestIndex
        }
        return result.toString().trim()
    }

    private const val BLANK_INDEX = 0
    private const val CHARACTER_SET =
        "0123456789!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ �ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.!?,'"
    private val ALLOWED_INDICES = intArrayOf(BLANK_INDEX) +
        CHARACTER_SET.indices.filter { CHARACTER_SET[it] in ALLOWED }.map { it + 1 }
}
