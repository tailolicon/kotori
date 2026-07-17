package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import logcat.LogPriority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tachiyomi.core.common.util.system.logcat
import java.text.Normalizer

/**
 * Built-in novel source for MeTruyenChu / Nôvel Fever (metruyenchuvn.com).
 *
 * No login and no obfuscation: chapter prose is served in the page HTML under `#vungdoc .truyen`,
 * separated by `<br>` rather than paragraphs. The chapter *list* is the one thing the page loads
 * over AJAX — `GET /get/listchap/{bid}?page=N` returns `{"data": "<ul><li><a …>"}` — so the story's
 * numeric id is read from the hidden `bid` input on the novel page and paged through here.
 */
class NovelFeverSource : BuiltInNovelSource() {

    override val name = "Nôvel Fever (MeTruyenChu)"
    override val baseUrl = "https://metruyenchuvn.com"
    override val supportsLatest = true
    override val iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=metruyenchuvn.com"

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", "$baseUrl/")

    // ============================== Browse ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = listing("truyen-hot", page)

    /**
     * The site has no "recently updated" listing — /danh-sach only serves truyen-hot and
     * truyen-full — but its home page is exactly that list, so read it from there. It isn't paged,
     * so later pages have nothing to add.
     */
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.newCall(GET("$baseUrl/", headers)).awaitSuccess().asJsoup()
        return document.toMangasPage().copy(hasNextPage = false)
    }

    /**
     * Genre options must be named exactly as [getNovelDetails] writes them into `genre`: the app
     * matches a tapped genre against these by name, and a mismatch silently degrades into a title
     * search — which no genre name will ever match.
     */
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Bỏ trống ô tìm kiếm khi lọc theo thể loại"),
        GenreFilter(),
    )

    /**
     * Genres arrive as a filter, not a query: the app matches a tapped genre against the names in
     * [getFilterList] and passes the filter instead. Authors have no such mechanism — the app can
     * only search their name as text — so a query that finds no title is retried as an author.
     */
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected()
        if (genre != null && query.isBlank()) return path("/the-loai/$genre", page)
        if (query.isBlank()) return listing("truyen-hot", page)

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        val byTitle = client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
        if (byTitle.mangas.isNotEmpty() || page > 1) return byTitle

        // Nothing by title: the query may well be an author's name.
        return runCatching { path("/tac-gia/${query.toSlug()}", page) }
            .getOrDefault(byTitle)
            .takeIf { it.mangas.isNotEmpty() } ?: byTitle
    }

    private suspend fun path(path: String, page: Int): MangasPage {
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
    }

    private suspend fun listing(path: String, page: Int): MangasPage {
        val url = "$baseUrl/danh-sach/$path".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
    }

    private fun Document.toMangasPage(): MangasPage {
        // Novels sit at the site root as /<slug>, so a single-segment link is the story link.
        val entries = select("a[href~=^/[a-z0-9-]+$]")
            .distinctBy { it.attr("href") }
            .mapNotNull { link ->
                val href = link.attr("href")
                val title = link.attr("title").ifBlank { link.text() }.trim()
                if (title.isBlank() || href in NON_STORY_PATHS) return@mapNotNull null
                SManga.create().apply {
                    url = href
                    // Listing links carry an SEO suffix the story itself doesn't have.
                    this.title = title.removeSuffix(TITLE_SUFFIX).trim()
                    // Covers are served off the slug; the listing markup doesn't carry them.
                    thumbnail_url = link.selectFirst("img")?.imageUrl() ?: coverFor(href)
                }
            }
        val hasNext = selectFirst("a[href*=page=]:containsOwn(»), .pagination a[rel=next]") != null
        return MangasPage(entries, hasNext)
    }

    /** Card images are site-relative, so resolve them or Coil is handed a path it can't fetch. */
    private fun Element.imageUrl(): String? =
        listOf("data-src", "data-original", "src")
            .firstNotNullOfOrNull { absUrl(it).takeIf(String::isNotBlank) }

    // ============================== Details ==============================

    override suspend fun getNovelDetails(novel: SManga): SManga {
        val document = client.newCall(GET(baseUrl + novel.url, headers)).awaitSuccess().asJsoup()
        return SManga.create().apply {
            url = novel.url
            title = (document.selectFirst("h1")?.text()?.trim() ?: novel.title)
                .removeSuffix(TITLE_SUFFIX).trim()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: coverFor(novel.url)
            author = document.selectFirst("[itemprop=author]")?.text()?.trim()
                ?: document.infoAfter("Tác giả")
            // Only this novel's genres — a page-wide selector sweeps up the site's genre menu too.
            genre = document.select("li.li--genres a").joinToString { it.text().trim() }
            description = document.selectFirst("[itemprop=description]")?.wholeText()?.trim()
                ?: document.infoAfter("Giới thiệu")
            status = when (document.selectFirst("span.label-status")?.text()?.trim()) {
                "Full", "Hoàn thành" -> SManga.COMPLETED
                "Đang ra", "Đang cập nhật" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    /** Reads a "Label: value" info row, returning the value. */
    private fun Document.infoAfter(label: String): String? = select("div, li, p, span")
        .firstOrNull { it.ownText().trim().startsWith("$label:") }
        ?.ownText()?.substringAfter(":")?.trim()
        ?.takeIf { it.isNotBlank() }

    // ============================== Chapters ==============================

    override suspend fun getChapterList(novel: SManga): List<SChapter> {
        val document = client.newCall(GET(baseUrl + novel.url, headers)).awaitSuccess().asJsoup()
        val bid = document.selectFirst("input[name=bid]")?.attr("value")
        if (bid.isNullOrBlank()) {
            logcat(LogPriority.WARN) { "NovelFever: no bid input on ${novel.url}" }
            return emptyList()
        }

        // Past the last page the endpoint wraps back to page 1 rather than returning nothing, so
        // stop once a page stops contributing new chapters — waiting for an empty one never ends.
        val chapters = mutableListOf<SChapter>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= MAX_CHAPTER_PAGES) {
            val batch = fetchChapterPage(bid, page, novel.url).filter { seen.add(it.url) }
            if (batch.isEmpty()) break
            chapters += batch
            page++
        }
        logcat(LogPriority.INFO) { "NovelFever: bid=$bid gave ${chapters.size} chapters over ${page - 1} page(s)" }
        // Site lists oldest-first; the app expects newest-first.
        return chapters.reversed()
    }

    private suspend fun fetchChapterPage(bid: String, page: Int, novelUrl: String): List<SChapter> {
        val request = GET(
            "$baseUrl/get/listchap/$bid?page=$page",
            headers.newBuilder()
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Referer", baseUrl + novelUrl)
                .build(),
        )
        val body = client.newCall(request).awaitSuccess().use { it.body.string() }
        val html = try {
            (json.parseToJsonElement(body) as? JsonObject)?.get("data")?.jsonPrimitive?.content
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "NovelFever: listchap page $page unparseable (${body.take(80)})" }
            null
        } ?: return emptyList()

        return Jsoup.parse(html, baseUrl).select("a[href]").mapNotNull { link ->
            // Chapter tokens contain characters Jsoup won't resolve into an absolute URL (it
            // returns "" and setUrlWithoutDomain then NPEs), but the href is already site-relative.
            val href = link.attr("href").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            SChapter.create().apply {
                url = href
                name = link.text().trim()
                // Without a number the app can't order chapters or pick the next one, so "Start"
                // lands on whatever the list happens to hold first.
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }
    }

    // ============================== Chapter text ==============================

    override suspend fun getChapterText(chapter: SChapter): String {
        val document = client.newCall(GET(baseUrl + chapter.url, headers)).awaitSuccess().asJsoup()
        val body = document.selectFirst("#vungdoc .truyen") ?: document.selectFirst("#vungdoc")
            ?: return "Không đọc được nội dung chương."

        body.select("script, style, ins, iframe, .ads, [class*=quang-cao]").remove()
        // Paragraphs are <br>-separated here rather than wrapped in <p>.
        return body.html()
            .replace(BR_REGEX, "\n")
            .let { Jsoup.parse(it).wholeText() }
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(BLANK_RUN_REGEX, "\n\n")
            .trim()
    }

    /** Covers live under /media/book/<slug>.jpg, so they can be derived when markup omits them. */
    private fun coverFor(novelUrl: String): String =
        "$baseUrl/media/book/${novelUrl.trim('/')}.jpg"

    /**
     * Turns an author's name into the slug their page lives at: "Diệp Phàm" -> "diep-pham".
     * Normalising strips the combining accents, but đ/Đ carry no accent to strip and need mapping.
     */
    private fun String.toSlug(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .replace('đ', 'd')
        .replace('Đ', 'd')
        .lowercase()
        .replace(NON_SLUG_CHARS, "-")
        .trim('-')

    private class GenreFilter : Filter.Select<String>("Thể loại", GENRE_NAMES) {
        fun selected(): String? = GENRE_SLUGS_ORDERED.getOrNull(state)?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TITLE_SUFFIX = "đọc online"
        private val COMBINING_MARKS = Regex("""\p{M}+""")
        private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")

        // The site's genre pages, as linked from its own nav. Held here so a genre tapped on a
        // novel can be recognised without a lookup request.
        private val GENRE_NAMES = arrayOf("Bất kỳ", "Bách Hợp", "Cung Đấu", "Cổ Đại", "Dị Giới", "Dị Năng", "Gia Đấu", "Góc Nhìn Nam", "Hiện Đại", "Huyền Huyễn", "Hệ Thống", "Khoa Huyễn", "Khác", "Kiếm Hiệp", "Light Novel", "Linh Dị", "Lịch Sử", "Mạt Thế", "Ngôn Tình", "Ngược", "Nữ Cường", "Nữ Phụ", "Quan Trường", "Quân Sự", "Sắc", "Sủng", "Teen", "Tiên Hiệp", "Trinh Thám", "Trọng Sinh", "Võng Du", "Xuyên Không", "Xuyên Nhanh", "Đam Mỹ", "Điền Văn", "Đoản Văn", "Đô Thị", "Đông Phương")
        private val GENRE_SLUGS_ORDERED = listOf("", "bach-hop", "cung-dau", "co-dai", "di-gioi", "di-nang", "gia-dau", "goc-nhin-nam", "hien-dai", "huyen-huyen", "he-thong", "khoa-huyen", "khac", "kiem-hiep", "light-novel", "linh-di", "lich-su", "mat-the", "ngon-tinh", "nguoc", "nu-cuong", "nu-phu", "quan-truong", "quan-su", "sac", "sung", "truyen-teen", "tien-hiep", "trinh-tham", "trong-sinh", "vong-du", "xuyen-khong", "xuyen-nhanh", "dam-my", "dien-van", "doan-van", "do-thi", "dong-phuong")
        private val GENRE_SLUGS = GENRE_SLUGS_ORDERED.filter { it.isNotEmpty() }.toSet()
        private val CHAPTER_NUMBER_REGEX = Regex("""(?:chương|chuong)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        // The chapter list is paged; stop rather than loop forever if the site keeps answering.
        private const val MAX_CHAPTER_PAGES = 300
        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val BLANK_RUN_REGEX = Regex("""\n{3,}""")
        private val NON_STORY_PATHS = setOf("/", "/search", "/danh-sach", "/the-loai", "/dang-nhap", "/dang-ky")
    }
}
