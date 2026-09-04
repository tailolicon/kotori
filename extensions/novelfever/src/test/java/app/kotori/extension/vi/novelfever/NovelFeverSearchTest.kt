package app.kotori.extension.vi.novelfever

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Titles here are real Novel Fever catalogue entries, and every expectation was checked against the
 * live `filter[keyword]` endpoint first: the cases that read "the server finds nothing" are cases
 * the server really does return an empty page for.
 */
class NovelFeverSearchTest {

    private val entries = listOf(
        entry("Gen Z Tại Tu Tiên Giới"),
        entry("Ta Muốn Tu Tiên, Ta Không Muốn Làm Ông Trùm Truyền Thông Giải Trí"),
        entry("Toàn Chức Pháp Sư Dị Bản"),
        entry("Đấu La: Khí Vận Chi Nữ? Cơ Duyên? Tất Cả Là Của Ta!"),
        entry("Bắt Đầu Cùng Chị Dâu Nương Tựa Lẫn Nhau"),
        entry("Lãng Nhân: Mỹ Nữ, Mời Tư Vấn", author = "Giang Tự"),
        entry("Dị Nhân Đại Náo Tu Tiên Giới"),
    )

    private fun entry(title: String, author: String? = null) = NovelFeverSearch.Entry(title, author)

    private fun titlesFor(query: String) = NovelFeverSearch.match(entries, query)
        .map { entries[it].title }

    @Test
    fun `normalize strips diacritics, lowers case and folds d-bar`() {
        assertEquals("dau la dai luc", NovelFeverSearch.normalize("Đấu La Đại Lục"))
        assertEquals("gen z tai tu tien gioi", NovelFeverSearch.normalize("Gen Z Tại Tu Tiên Giới"))
        // Punctuation separates rather than glues, so "Lãng Nhân: Mỹ Nữ" is four words.
        assertEquals("lang nhan my nu moi tu van", NovelFeverSearch.normalize("Lãng Nhân: Mỹ Nữ, Mời Tư Vấn"))
    }

    @Test
    fun `an accented query matches an unaccented one and the other way round`() {
        assertEquals(titlesFor("tu tien"), titlesFor("Tu Tiên"))
        assertTrue(titlesFor("dau la").isNotEmpty())
    }

    @Test
    fun `words are matched in any order, which is what the server cannot do`() {
        // The server needs the words contiguous: "gen tien" returns nothing from it.
        assertEquals(listOf("gen z tai tu tien gioi"), titlesFor("gen tien"))
        assertEquals(listOf("gen z tai tu tien gioi"), titlesFor("z tien"))
    }

    @Test
    fun `a contiguous match still leads the results`() {
        val results = titlesFor("tu tien")
        assertEquals("gen z tai tu tien gioi", results.first())
        assertTrue(results.size > 1)
    }

    @Test
    fun `short tokens must be whole syllables`() {
        // "la" as a prefix would also match "Lẫn" in "Bắt Đầu Cùng Chị Dâu Nương Tựa Lẫn Nhau",
        // and two loose tokens like that are enough to fill the screen with noise.
        assertTrue(titlesFor("dau la").none { it.startsWith("bat dau") })
    }

    @Test
    fun `the author is searchable even though the server never indexes it`() {
        assertEquals(listOf("lang nhan my nu moi tu van"), titlesFor("Giang Tự"))
    }

    @Test
    fun `a query missing by one word falls back, but only from three words up`() {
        // Three of "pham nhan tu tien" land on the "Ta Muốn Tu Tiên" entry; nothing carries all four.
        assertTrue(titlesFor("phàm nhân tu tiên").isNotEmpty())
        // Two words are too few to be one word wrong — an honest empty result beats a guess.
        assertEquals(emptyList<String>(), titlesFor("kiếm hiệp"))
    }

    @Test
    fun `a query nothing answers stays empty`() {
        assertEquals(emptyList<String>(), titlesFor("Đấu La Đại Lục Ngoại Truyện Hoàn Toàn Khác"))
        assertEquals(emptyList<String>(), titlesFor("   "))
    }
}
