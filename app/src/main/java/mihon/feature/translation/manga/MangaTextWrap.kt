package mihon.feature.translation.manga

import kotlin.math.sqrt

/**
 * Port of Manga-Translator `add_text.smart_wrap_text` / `calculate_optimal_font_size`.
 *
 * Font measurement is injected so the same wrap rules can be unit-tested without Android.
 */
internal object MangaTextWrap {

    const val MIN_FONT_SIZE = 10
    const val MAX_FONT_SIZE = 60
    const val PADDING_RATIO = 0.1f

    fun smartWrap(text: String, charsPerLine: Int): String {
        if (text.isEmpty() || charsPerLine <= 0) return text
        val firstPass = wrapWords(text, charsPerLine)
        val result = ArrayList<String>()
        for (line in firstPass) {
            if (line.length <= charsPerLine) {
                result += line
                continue
            }
            val words = line.split(' ')
            var current = ""
            for (word in words) {
                current = when {
                    current.isEmpty() -> word
                    current.length + 1 + word.length <= charsPerLine -> "$current $word"
                    else -> {
                        result += current
                        word
                    }
                }
            }
            if (current.isNotEmpty()) result += current
        }
        return result.joinToString("\n")
    }

    fun optimalSize(
        text: String,
        width: Int,
        height: Int,
        measure: (String, Int) -> Float,
    ): Triple<Int, Int, String> {
        val usableW = (width * (1 - 2 * PADDING_RATIO)).toInt()
        val usableH = (height * (1 - 2 * PADDING_RATIO)).toInt()
        if (usableW <= 0 || usableH <= 0) {
            return Triple(MIN_FONT_SIZE, MIN_FONT_SIZE, text)
        }
        val bubbleArea = usableW.toLong() * usableH
        val charCount = text.length.coerceAtLeast(1)
        val estimated = sqrt(bubbleArea / (charCount * 0.8)).toInt()
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

        var bestSize = MIN_FONT_SIZE
        var bestWrapped = text
        var size = estimated
        while (size >= MIN_FONT_SIZE) {
            val lineHeight = (size * 1.3).toInt()
            val avgCharWidth = size * 0.6f
            val charsPerLine = (usableW / avgCharWidth).toInt().coerceAtLeast(1)
            val wrapped = smartWrap(text, charsPerLine)
            val lines = wrapped.split('\n')
            val totalHeight = lines.size * lineHeight
            if (totalHeight <= usableH) {
                val fitsWidth = lines.all { line ->
                    val lineWidth = runCatching { measure(line, size) }
                        .getOrElse { line.length * avgCharWidth }
                    lineWidth <= usableW
                }
                if (fitsWidth) {
                    bestSize = size
                    bestWrapped = wrapped
                    break
                }
            }
            size -= 2
        }
        return Triple(bestSize, (bestSize * 1.3).toInt(), bestWrapped)
    }

    private fun wrapWords(text: String, width: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            current = when {
                current.isEmpty() -> word
                current.length + 1 + word.length <= width -> "$current $word"
                else -> {
                    lines += current
                    word
                }
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
