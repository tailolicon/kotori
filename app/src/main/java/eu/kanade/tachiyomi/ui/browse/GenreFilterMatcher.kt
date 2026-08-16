package eu.kanade.tachiyomi.ui.browse

/**
 * Matches a user-facing genre name to a source filter.
 *
 * Browse chips and the manga-detail tag row both speak Vietnamese ("Hành động"). Hitomi and most
 * English catalogues speak "Action". Without this mapping a tap on the chip becomes a text search
 * for the Vietnamese word, which those catalogues treat as a title query and answer with a handful
 * of misses — then report the list is finished.
 */
object GenreFilterMatcher {

    fun namesMatch(left: String, right: String): Boolean {
        if (left.equals(right, ignoreCase = true)) return true
        val wanted = aliases(left).map { it.lowercase() }.toSet()
        return aliases(right).any { it.lowercase() in wanted }
    }

    fun selectIndex(values: List<String>, genreName: String): Int {
        val direct = values.indexOfFirst { it.equals(genreName, ignoreCase = true) }
        if (direct >= 0) return direct
        val wanted = aliases(genreName).map { it.lowercase() }.toSet()
        return values.indexOfFirst { aliases(it).any { alias -> alias.lowercase() in wanted } }
    }

    fun languageAllIndex(values: List<String>): Int {
        return values.indexOfFirst { value ->
            value.equals("All", ignoreCase = true) ||
                value.equals("Tất cả", ignoreCase = true) ||
                value.equals("any", ignoreCase = true)
        }
    }

    fun isTagTextFilter(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("tag") ||
            n.contains("genre") ||
            n.contains("thể loại") ||
            n.contains("the loai")
    }

    /**
     * The bare tag, without the gender marker sources append for display.
     *
     * A tag row shows "Sole Female ♀"; the catalogue knows it as "sole female" in a gendered
     * namespace. Searching the displayed string finds entries with a ♀ in the *title* instead.
     */
    fun strippedTag(genreName: String): String =
        genreName.trim().removeSuffix(FEMALE).removeSuffix(MALE).trim()

    /**
     * Which text filter a tag belongs in, best match first.
     *
     * Namespaces are picked off the marker: "Sole Female ♀" belongs in the source's female-tag
     * field, never in the plain one — and an unmarked tag belongs in the plain field, which is why
     * a plain "contains tag" test is not enough. It matches "Male Tags" first, and the source
     * itself warns that a genre put there returns nothing.
     */
    fun tagFilterRank(filterName: String, genreName: String): Int {
        val n = filterName.lowercase()
        val female = n.contains("female") || n.contains("nữ")
        val male = !female && (n.contains("male") || n.contains("nam"))
        val plain = isTagTextFilter(filterName) && !female && !male
        return when {
            genreName.trim().endsWith(FEMALE) -> if (female) 0 else -1
            genreName.trim().endsWith(MALE) -> if (male) 0 else -1
            plain -> if (n == "tags" || n == "tag" || n.contains("genre")) 0 else 1
            else -> -1
        }
    }

    /**
     * Which text filter an author, artist or circle belongs in, best match first; -1 for none.
     *
     * Catalogues index people apart from prose. On Hitomi `artist/<name>-all.nozomi` is a real
     * listing while the same name is absent from the text index altogether, so a creator searched
     * as free text finds nothing at all — the failure looks like the site has nothing by them.
     *
     * [preferGroup] picks which of the two fields wins when a source offers both: the entry's
     * author line is a circle on doujinshi catalogues, the artist line a person.
     */
    fun creatorFilterRank(filterName: String, preferGroup: Boolean): Int {
        val n = filterName.lowercase()
        val group = n.contains("group") || n.contains("circle") || n.contains("nhóm")
        val artist = !group && (
            n.contains("artist") || n.contains("author") ||
                n.contains("tác giả") || n.contains("tac gia")
            )
        return when {
            group -> if (preferGroup) 0 else 1
            artist -> if (preferGroup) 1 else 0
            else -> -1
        }
    }

    /**
     * Whether this name is one of Browse's own genre chips rather than a tag a source handed us.
     *
     * The two need different treatment. A source's tag is real on that site, so it belongs in the
     * matching tag filter. A chip is this app's vocabulary — "Hành động" has no counterpart on a
     * doujinshi catalogue, and forcing it into a tag filter asks for a listing that does not exist
     * and gets a 404. A plain text query at least matches on titles.
     */
    fun isGenericChip(name: String): Boolean {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return false
        if (ALIASES.containsKey(key)) return true
        return ALIASES.values.any { values -> values.any { it.equals(key, ignoreCase = true) } }
    }

    private const val FEMALE = "♀"
    private const val MALE = "♂"

    fun aliases(name: String): List<String> {
        val key = name.trim()
        if (key.isEmpty()) return emptyList()
        ALIASES[key.lowercase()]?.let { return listOf(key) + it }
        ALIASES.entries.firstOrNull { (_, values) ->
            values.any { it.equals(key, ignoreCase = true) }
        }?.let { return listOf(it.key) + it.value }
        return listOf(key)
    }

    private val ALIASES = mapOf(
        "hành động" to listOf("Action", "action"),
        "phiêu lưu" to listOf("Adventure", "adventure"),
        "hài hước" to listOf("Comedy", "comedy"),
        "tình cảm" to listOf("Romance", "romance"),
        "học đường" to listOf("School Life", "school", "schoolgirl"),
        "kinh dị" to listOf("Horror", "horror"),
        "viễn tưởng" to listOf("Sci-Fi", "science fiction", "scifi"),
        "huyền ảo" to listOf("Fantasy", "fantasy"),
        "trinh thám" to listOf("Mystery", "mystery"),
        "thể thao" to listOf("Sports", "sports"),
        "đời thường" to listOf("Slice of Life", "slice of life"),
        "kiếm hiệp" to listOf("Martial Arts", "martial arts"),
        "drama" to listOf("Drama", "drama"),
        "ecchi" to listOf("Ecchi", "ecchi"),
        "harem" to listOf("Harem", "harem"),
    )
}
