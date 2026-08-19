package mihon.feature.translation

import kotlin.math.max
import kotlin.math.min

/**
 * Drops Japanese ruby (furigana) so it is not treated as its own line of dialogue.
 *
 * Ruby is a short run of kana set at about half the type size of the word it annotates, sitting
 * beside a vertical column or above a horizontal one. ML Kit reports it as a separate line, and
 * translating that line letters a reading into the balloon next to the real sentence. Geometry plus
 * script is enough — no title-specific vocabulary.
 */
internal object FuriganaGuard {

    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        /** Median glyph thickness. Falls back to the box short side when the caller has no lines. */
        val stroke: Int = 0,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val typeSize: Int get() = (if (stroke > 0) stroke else min(width, height)).coerceAtLeast(1)
    }

    /** Indices of [lines] that are ruby for a neighbouring Japanese host. */
    fun dropIndices(lines: List<Line>): Set<Int> {
        if (lines.size < 2) return emptySet()
        val dropped = HashSet<Int>()
        for (index in lines.indices) {
            val ruby = lines[index]
            if (!isRubyScript(ruby.text)) continue
            val hosted = lines.indices.any { hostIndex ->
                hostIndex != index && isHost(lines[hostIndex], ruby)
            }
            if (hosted) dropped += index
        }
        return dropped
    }

    fun isRubyScript(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty() || letters.length > MAX_RUBY_LETTERS) return false
        val kana = letters.count(::isKana)
        val kanji = letters.count(::isKanji)
        return kana >= MIN_RUBY_KANA &&
            kanji == 0 &&
            kana * 4 >= letters.length * 3
    }

    private fun isHost(host: Line, ruby: Line): Boolean {
        if (!hasKanji(host.text)) return false
        if (ruby.typeSize * RUBY_SIZE_DENOMINATOR > host.typeSize * RUBY_SIZE_NUMERATOR) return false
        // A neighbouring dialogue balloon is roughly the same width as its host. Ruby is a thin
        // strip: much narrower (vertical) or much shorter (horizontal) than the word it annotates.
        val vertical = host.height > host.width * VERTICAL_ASPECT
        val thin = if (vertical) {
            ruby.width * RUBY_THIN_DENOMINATOR <= host.width * RUBY_THIN_NUMERATOR
        } else {
            ruby.height * RUBY_THIN_DENOMINATOR <= host.height * RUBY_THIN_NUMERATOR
        }
        if (!thin) return false
        return adjacentRuby(ruby, host)
    }

    private fun adjacentRuby(ruby: Line, host: Line): Boolean {
        val vertical = host.height > host.width * VERTICAL_ASPECT
        val maxGap = max(host.typeSize, ruby.typeSize) * MAX_RUBY_GAP
        return if (vertical) {
            val overlap = min(ruby.bottom, host.bottom) - max(ruby.top, host.top)
            overlap > 0 && horizontalGap(ruby, host) <= maxGap
        } else {
            val overlap = min(ruby.right, host.right) - max(ruby.left, host.left)
            overlap > 0 && verticalGap(ruby, host) <= maxGap
        }
    }

    private fun horizontalGap(a: Line, b: Line): Int = when {
        a.left >= b.right -> a.left - b.right
        b.left >= a.right -> b.left - a.right
        else -> 0
    }

    private fun verticalGap(a: Line, b: Line): Int = when {
        a.top >= b.bottom -> a.top - b.bottom
        b.top >= a.bottom -> b.top - a.bottom
        else -> 0
    }

    private fun hasKanji(text: String): Boolean = text.any(::isKanji)

    private fun isKana(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x3040..0x309F || cp in 0x30A0..0x30FF || cp in 0x31F0..0x31FF
    }

    private fun isKanji(ch: Char): Boolean = ch.code.let { cp ->
        cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF
    }

    const val MAX_RUBY_LETTERS = 8
    const val MIN_RUBY_KANA = 1
    /** Ruby is at most this fraction of the host type (numerator/denominator). */
    const val RUBY_SIZE_NUMERATOR = 11
    const val RUBY_SIZE_DENOMINATOR = 20
    /** Ruby strip is at most this fraction of the host's long-axis size. */
    const val RUBY_THIN_NUMERATOR = 1
    const val RUBY_THIN_DENOMINATOR = 2
    const val VERTICAL_ASPECT = 1.5f
    const val MAX_RUBY_GAP = 1.4f
}
