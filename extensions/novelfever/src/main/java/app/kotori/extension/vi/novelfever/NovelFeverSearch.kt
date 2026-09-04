package app.kotori.extension.vi.novelfever

import java.text.Normalizer

/**
 * Title matching for [NovelFever], over plain strings only so it can be exercised by a JVM test.
 *
 * The API's own `filter[keyword]` is a *contiguous substring* match over the accent-stripped title
 * and nothing else: "z tai tu" finds "Gen Z Tại Tu Tiên Giới", "gen tien" finds nothing, and an
 * author's name finds nothing at all. Readers type titles from memory and in their own order, so
 * the search box comes back empty far more often than the catalogue actually warrants. What is here
 * matches the same normalised shape the server compares in, then keeps going where it stops.
 */
internal object NovelFeverSearch {

    /**
     * Lower case, no diacritics, letters and digits only — the shape the API's keyword filter
     * compares in, so "Đấu La" and "dau la" are one search here exactly as they are there.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (character in decomposed) {
            when {
                character in 'a'..'z' || character in '0'..'9' -> out.append(character)
                // NFD leaves đ alone — it is a letter in its own right, not d plus a mark.
                character == 'đ' -> out.append('d')
                Character.getType(character) == Character.NON_SPACING_MARK.toInt() -> Unit
                out.isNotEmpty() && out.last() != ' ' -> out.append(' ')
            }
        }
        return out.toString().trim()
    }

    /** One catalogue book reduced to what a search looks at. */
    class Entry(rawTitle: String, rawAuthor: String?) {
        val title: String = normalize(rawTitle)
        val words: List<String> = normalize(listOfNotNull(rawTitle, rawAuthor).joinToString(" "))
            .split(' ')
            .filter(String::isNotEmpty)

        /**
         * How many of [tokens] this entry carries.
         *
         * One- and two-letter tokens are whole Vietnamese syllables and are matched as such: read
         * as prefixes, "la" also matches "lẫn" and "lạnh", and the result list turns to noise.
         */
        fun hits(tokens: List<String>): Int = tokens.count { token ->
            if (token.length <= SYLLABLE_PREFIX_MIN) {
                words.any { it == token }
            } else {
                words.any { it.startsWith(token) }
            }
        }
    }

    /**
     * Positions in [entries] that answer [query], best first.
     *
     * Three tiers, and the first non-empty one wins:
     * 1. what the server itself would have matched, so a local answer is never the worse answer;
     * 2. every word of the query present somewhere in title or author, in any order;
     * 3. only when nothing carries the whole query — what misses by a single word, since a
     *    half-remembered title is the usual reason for getting this far. Two words are too few to
     *    be one word wrong, so short queries stop at an honest empty result instead.
     */
    fun match(entries: List<Entry>, query: String): List<Int> {
        val key = normalize(query)
        val tokens = key.split(' ').filter(String::isNotEmpty)
        if (tokens.isEmpty()) return emptyList()

        val substring = entries.indices.filter { key in entries[it].title }
        val taken = substring.toHashSet()
        val complete = entries.indices
            .filter { it !in taken && entries[it].hits(tokens) == tokens.size }
            .sortedBy { entries[it].title.length }
        if (substring.isNotEmpty() || complete.isNotEmpty()) return substring + complete

        if (tokens.size < MIN_TOKENS_FOR_NEAR_MISS) return emptyList()
        return entries.indices
            .map { it to entries[it].hits(tokens) }
            .filter { it.second >= tokens.size - 1 }
            .sortedWith(
                compareByDescending<Pair<Int, Int>> { it.second }
                    .thenBy { entries[it.first].title.length },
            )
            .map { it.first }
    }

    private const val SYLLABLE_PREFIX_MIN = 2
    private const val MIN_TOKENS_FOR_NEAR_MISS = 3
}
