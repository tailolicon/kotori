package mihon.feature.translation.manga

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Port of Manga-Translator `add_text.smart_wrap_text` / `calculate_optimal_font_size`.
 *
 * Font measurement is injected so the same wrap rules can be unit-tested without Android.
 */
internal object MangaTextWrap {

    /**
     * Font bounds in the Python original, which are absolute pixel counts.
     *
     * They only work at the size the reference implementation happens to be fed. Kotori translates
     * pages at native resolution, so the same balloon arrives at 900 px wide from one source and
     * 2069 px from another, and a fixed 10..60 band means the second one gets type a reader has to
     * squint at — with 10 px on a 2069 px page being simply invisible. [boundsFor] keeps the same
     * numbers for a roughly 1000 px page and scales them with the page from there.
     */
    const val MIN_FONT_SIZE = 10
    const val MAX_FONT_SIZE = 60
    const val REFERENCE_PAGE_WIDTH = 1000f
    const val PADDING_RATIO = 0.1f

    /**
     * @param size the font size chosen
     * @param lineHeight distance between baselines
     * @param wrapped [text] with newlines inserted
     * @param fits false when even the smallest legible size overflows the area — the caller should
     *   leave the artwork alone rather than stamp something unreadable over it
     */
    data class Fit(
        val size: Int,
        val lineHeight: Int,
        val wrapped: String,
        val fits: Boolean,
    )

    /** Smallest and largest type worth drawing on a page [pageWidth] pixels across. */
    fun boundsFor(pageWidth: Int): Pair<Int, Int> {
        val scale = (pageWidth / REFERENCE_PAGE_WIDTH).coerceIn(0.6f, 4f)
        val min = (MIN_FONT_SIZE * scale).roundToInt().coerceAtLeast(6)
        val max = (MAX_FONT_SIZE * scale).roundToInt().coerceAtLeast(min + 2)
        return min to max
    }

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
        minFont: Int = MIN_FONT_SIZE,
        maxFont: Int = MAX_FONT_SIZE,
        measure: (String, Int) -> Float,
    ): Fit {
        val usableW = (width * (1 - 2 * PADDING_RATIO)).toInt()
        val usableH = (height * (1 - 2 * PADDING_RATIO)).toInt()
        if (usableW <= 0 || usableH <= 0) {
            return Fit(minFont, minFont, text, fits = false)
        }
        val bubbleArea = usableW.toLong() * usableH
        val charCount = text.length.coerceAtLeast(1)
        val estimated = sqrt(bubbleArea / (charCount * 0.8)).toInt().coerceIn(minFont, maxFont)

        var size = estimated
        while (size >= minFont) {
            val lineHeight = (size * 1.3).toInt()
            val avgCharWidth = size * 0.6f
            val charsPerLine = (usableW / avgCharWidth).toInt().coerceAtLeast(1)
            val wrapped = smartWrap(text, charsPerLine)
            val lines = wrapped.split('\n')
            if (lines.size * lineHeight <= usableH) {
                val fitsWidth = lines.all { line ->
                    val lineWidth = runCatching { measure(line, size) }
                        .getOrElse { line.length * avgCharWidth }
                    lineWidth <= usableW
                }
                if (fitsWidth) return Fit(size, lineHeight, wrapped, fits = true)
            }
            size -= 2
        }
        // Nothing legible fits. Report the smallest attempt so a caller that insists can still draw,
        // but say plainly that it will not be readable.
        val lineHeight = (minFont * 1.3).toInt()
        val charsPerLine = (usableW / (minFont * 0.6f)).toInt().coerceAtLeast(1)
        return Fit(minFont, lineHeight, smartWrap(text, charsPerLine), fits = false)
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
