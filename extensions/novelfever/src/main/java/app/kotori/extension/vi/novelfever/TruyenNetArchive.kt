package app.kotori.extension.vi.novelfever

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.novel.NovelChapterHtml
import org.jsoup.Jsoup

/**
 * A narrowly scoped archive fallback for a title readers already had under the old source name.
 *
 * Novel Fever's current API no longer indexes Chấp Ma, but TruyenNet still serves the complete
 * 1,504-chapter run. Keeping this adapter explicit prevents unrelated titles from ever being
 * joined to a second site's catalogue while allowing the reported title to remain readable.
 */
internal object TruyenNetArchive {

    const val BASE_URL = "https://truyennet.org"
    const val NOVEL_URL = "/external/truyennet/chap-ma"
    const val NOVEL_PATH = "/chap-ma"

    private const val TITLE = "Chấp Ma"
    private const val AUTHOR = "Ngã Thị Mặc Thủy"
    private const val COVER_PATH = "/media/book/chap-ma-7wjpPv9H.jpg"
    private const val CHAPTER_URL_PREFIX = "/external/truyennet"

    private val aliases = listOf(
        NovelFeverSearch.normalize(TITLE),
        NovelFeverSearch.normalize("Hợp Thể Song Tu"),
        NovelFeverSearch.normalize("Chấp Ma Hợp Thể Song Tu"),
    )

    fun matches(query: String): Boolean {
        val key = NovelFeverSearch.normalize(query)
        if (key.length < MIN_QUERY_LENGTH) return false
        return aliases.any { alias -> key in alias || alias in key }
    }

    fun searchResult(): SManga = SManga.create().apply {
        url = NOVEL_URL
        title = TITLE
        author = AUTHOR
        thumbnail_url = "$BASE_URL$COVER_PATH"
    }

    fun isNovelUrl(url: String): Boolean = url == NOVEL_URL

    fun isChapterUrl(url: String): Boolean =
        url.startsWith("$CHAPTER_URL_PREFIX$NOVEL_PATH/chuong-")

    fun siteUrl(url: String): String = when {
        isNovelUrl(url) -> "$BASE_URL$NOVEL_PATH"
        isChapterUrl(url) -> BASE_URL + url.removePrefix(CHAPTER_URL_PREFIX)
        else -> error("URL lưu trữ TruyenNet không hợp lệ: $url")
    }

    fun chapterPageUrl(bookId: String, page: Int): String =
        "$BASE_URL/get/listchap/$bookId?page=$page"

    fun parseDetails(html: String, previous: SManga): Details {
        val document = Jsoup.parse(html, BASE_URL)
        val bookId = document.selectFirst("input[name=bid]")?.attr("value").orEmpty()
        require(bookId.isNotEmpty()) { "Trang Chấp Ma trên TruyenNet thiếu mã truyện" }

        val pageNumbers = document.select("#chapter-list .paging a[onclick]")
            .mapNotNull { element ->
                PAGE_CALL_REGEX.find(element.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull()
            }
        val lastPage = pageNumbers.maxOrNull() ?: 1

        val statusText = document.selectFirst(".label-status")?.text().orEmpty()
        val statusKey = NovelFeverSearch.normalize(statusText)
        val novel = SManga.create().apply {
            url = previous.url
            title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
                .orEmpty().ifEmpty { previous.title.ifEmpty { TITLE } }
            thumbnail_url = document.selectFirst("img[itemprop=image]")?.absUrl("src")
                ?.takeIf(String::isNotEmpty) ?: previous.thumbnail_url
            author = document.selectFirst("[itemprop=author]")?.text()?.trim()
                ?.takeIf(String::isNotEmpty) ?: previous.author ?: AUTHOR
            genre = document.select(".li--genres a").joinToString { it.text().trim() }
            description = document.selectFirst("[itemprop=description]")?.text()?.trim()
            status = when {
                "hoan" in statusKey -> SManga.COMPLETED
                "tam" in statusKey || "ngung" in statusKey -> SManga.ON_HIATUS
                statusKey.isNotEmpty() -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
        return Details(novel, bookId, lastPage)
    }

    fun parseChapters(html: String): List<SChapter> {
        val document = Jsoup.parseBodyFragment(html, BASE_URL)
        return document.select("#chapter-list li a, li a[href*=/chap-ma/chuong-]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                if (!href.startsWith("$NOVEL_PATH/chuong-")) return@mapNotNull null
                SChapter.create().apply {
                    url = CHAPTER_URL_PREFIX + href
                    name = anchor.text().trim()
                    chapter_number = CHAPTER_NUMBER_REGEX.find(name)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                }
            }
    }

    fun parseChapterText(html: String): String {
        val document = Jsoup.parse(html, BASE_URL)
        val story = document.selectFirst("#story")
            ?: throw IllegalStateException("Chương Chấp Ma trên TruyenNet không có nội dung")
        NovelChapterHtml.stripHiddenContent(story)

        val heading = document.selectFirst(".current-chapter")?.text()?.trim()
        val blocks = NovelChapterHtml.toBlocks(story).toMutableList()
        while (heading != null && blocks.firstOrNull()?.equals(heading, ignoreCase = true) == true) {
            blocks.removeAt(0)
        }
        blocks.firstOrNull()
            ?.takeIf(::isUpdateMetadata)
            ?.let { blocks.removeAt(0) }
        return blocks.joinToString("\n\n")
            .takeIf(String::isNotEmpty)
            ?: throw IllegalStateException("Nội dung chương Chấp Ma trên TruyenNet trống")
    }

    data class Details(
        val novel: SManga,
        val bookId: String,
        val lastPage: Int,
    )

    private const val MIN_QUERY_LENGTH = 4
    private val PAGE_CALL_REGEX = Regex("""page\(\d+,\s*(\d+)\)""")
    private val CHAPTER_NUMBER_REGEX = Regex("""(?i)chương\s+(\d+(?:\.\d+)?)""")

    private fun isUpdateMetadata(text: String): Boolean {
        val key = NovelFeverSearch.normalize(text)
        return key.startsWith("thoi gian doi moi") && "so luong tu" in key
    }
}
