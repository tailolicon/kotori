package eu.kanade.tachiyomi.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenreFilterMatcherTest {

    @Test
    fun `vietnamese chip matches english catalogue name`() {
        assertTrue(GenreFilterMatcher.namesMatch("Hành động", "Action"))
        assertTrue(GenreFilterMatcher.namesMatch("Action", "hành động"))
        assertEquals(2, GenreFilterMatcher.selectIndex(listOf("All", "Romance", "Action"), "Hành động"))
    }

    @Test
    fun `unrelated names do not match`() {
        assertFalse(GenreFilterMatcher.namesMatch("Hành động", "Romance"))
        assertEquals(-1, GenreFilterMatcher.selectIndex(listOf("All", "Romance"), "Kinh dị"))
    }

    @Test
    fun `tag text filters are recognised under several labels`() {
        assertTrue(GenreFilterMatcher.isTagTextFilter("Tags"))
        assertTrue(GenreFilterMatcher.isTagTextFilter("Thể loại"))
        assertTrue(GenreFilterMatcher.isTagTextFilter("Genre"))
        assertFalse(GenreFilterMatcher.isTagTextFilter("Language"))
        assertFalse(GenreFilterMatcher.isTagTextFilter("Sort"))
    }

    @Test
    fun `gender marker picks the namespaced filter and never the plain one`() {
        val filters = listOf("Groups", "Artists", "Male Tags", "Female Tags", "Tags")

        fun pick(genre: String) = filters
            .map { it to GenreFilterMatcher.tagFilterRank(it, genre) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first

        assertEquals("Female Tags", pick("Sole Female ♀"))
        assertEquals("Male Tags", pick("Dark Skin ♂"))
        // "Male Tags" contains "tag" and comes first — it must still lose to the plain field.
        assertEquals("Tags", pick("Digital"))
    }

    @Test
    fun `the gender marker is stripped from the searched value`() {
        assertEquals("Sole Female", GenreFilterMatcher.strippedTag("Sole Female ♀"))
        assertEquals("Dark Skin", GenreFilterMatcher.strippedTag("Dark Skin ♂"))
        assertEquals("Digital", GenreFilterMatcher.strippedTag("Digital"))
    }

    @Test
    fun `browse chips are not mistaken for a source's own tags`() {
        assertTrue(GenreFilterMatcher.isGenericChip("Hành động"))
        assertTrue(GenreFilterMatcher.isGenericChip("Action"))
        assertFalse(GenreFilterMatcher.isGenericChip("Sole Female ♀"))
        assertFalse(GenreFilterMatcher.isGenericChip("Digital"))
    }

    @Test
    fun `language all is the unfiltered option`() {
        assertEquals(0, GenreFilterMatcher.languageAllIndex(listOf("All", "English", "日本語")))
        assertEquals(0, GenreFilterMatcher.languageAllIndex(listOf("Tất cả", "Tiếng Việt")))
        assertEquals(-1, GenreFilterMatcher.languageAllIndex(listOf("English", "日本語")))
    }
}
