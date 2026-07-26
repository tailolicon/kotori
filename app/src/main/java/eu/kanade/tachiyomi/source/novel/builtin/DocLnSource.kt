package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.novel.NovelChapterHtml
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/**
 * Built-in novel source for docln.net (Cổng Light Novel / Hako).
 *
 * Plain server-rendered HTML — no login is needed for reading, and chapter text sits in
 * `#chapter-content` as `<p>` paragraphs. Series live under `/truyen/<id>-<slug>`; user-submitted
 * originals under `/sang-tac/<id>-<slug>`. Both are handled: listing cards link to whichever.
 */
class DocLnSource : BuiltInNovelSource() {

    override val name = "DocLN"

    /**
     * Same site, same markup, same `i.hako.vip` covers on each — they differ only in which one a
     * given network lets through. `.sbs` leads because it is the one currently reachable from
     * Vietnamese ISPs that reset the handshake for `docln.net` and `ln.hako.vn`.
     */
    override val mirrors = listOf("docln.sbs", "docln.net", "ln.hako.vn")

    override val baseUrl get() = "https://$domain"
    override val supportsLatest = true
    override val iconUrl = "https://docln.sbs/favicon.ico"
    override val loginUrl get() = "$baseUrl/login"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", "$baseUrl/")

    // ============================== Browse ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = listing("top", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("capnhat", page)

    /**
     * Genre options must be named exactly as [getNovelDetails] writes them into `genre`: the app
     * matches a tapped genre against these by name, and a mismatch silently degrades into a title
     * search — which no genre name will ever match.
     */
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Bỏ trống ô tìm kiếm khi lọc theo thể loại"),
        GenreFilter(),
    )

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected()
        if (genre != null && query.isBlank()) {
            val url = "$baseUrl/the-loai/$genre".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
            return client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
        }
        if (query.isBlank()) return listing("capnhat", page)
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("page", page.toString())
            .build()
        return client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
    }

    private class GenreFilter : Filter.Select<String>("Thể loại", GENRE_NAMES) {
        fun selected(): String? = GENRE_SLUGS.getOrNull(state)?.takeIf { it.isNotEmpty() }
    }

    private suspend fun listing(sort: String, page: Int): MangasPage {
        val url = "$baseUrl/danh-sach".toHttpUrl().newBuilder()
            .addQueryParameter("sapxep", sort)
            .addQueryParameter("page", page.toString())
            .build()
        return client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
    }

    private fun Document.toMangasPage(): MangasPage {
        val entries = select("div.thumb-item-flow").mapNotNull { card ->
            // The card's first link points at the newest chapter; only the series-title link is
            // the series itself.
            val link = card.selectFirst("div.thumb_attr.series-title a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text().trim()
                thumbnail_url = card.selectFirst("[data-bg]")?.attr("data-bg")
            }
        }
        // Covers are lazy-loaded, so a full page of cards means another page probably exists.
        val hasNext = selectFirst("ul.pagination a[rel=next], ul.pagination li.active + li a") != null
        return MangasPage(entries, hasNext)
    }

    // ============================== Details ==============================

    override suspend fun getNovelDetails(novel: SManga): SManga {
        val document = client.newCall(GET(baseUrl + novel.url, headers)).awaitSuccess().asJsoup()
        return SManga.create().apply {
            url = novel.url
            title = document.selectFirst(".series-name")?.text()?.trim() ?: novel.title
            thumbnail_url = document.selectFirst(".series-cover [data-bg], [data-bg]")?.attr("data-bg")
            author = document.infoItem("Tác giả")
            genre = document.select(".series-gerne-item").joinToString { it.text().trim() }
            description = document.selectFirst(".summary-content")?.wholeText()?.cleanText()
            status = when (document.infoItem("Tình trạng")) {
                "Đang tiến hành" -> SManga.ONGOING
                "Đã hoàn thành" -> SManga.COMPLETED
                "Tạm ngưng" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    /** Reads an `.info-item` row like "Tác giả: X", returning just the value. */
    private fun Document.infoItem(label: String): String? = select(".info-item")
        .firstOrNull { it.text().trim().startsWith("$label:") }
        ?.text()?.substringAfter(":")?.trim()
        ?.takeIf { it.isNotBlank() }

    // ============================== Chapters ==============================

    override suspend fun getChapterList(novel: SManga): List<SChapter> {
        val document = client.newCall(GET(baseUrl + novel.url, headers)).awaitSuccess().asJsoup()
        // Site lists oldest-first; the app expects newest-first.
        // Walked per row rather than per link because the posting date is the row's sibling: the
        // link alone cannot reach it, which is why every chapter used to be dated "today".
        return document.select("ul.list-chapters li")
            .mapNotNull { it.toSChapter() }
            .reversed()
    }

    private fun Element.toSChapter(): SChapter? {
        val link = selectFirst("div.chapter-name a") ?: return null
        return SChapter.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            name = link.text().trim()
            chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            date_upload = selectFirst("div.chapter-time")?.text()?.toEpochMillis() ?: 0L
        }
    }

    /**
     * Parses the site's `dd/MM/yyyy` posting date.
     *
     * A failure yields 0 rather than the current time: the reader's list then shows no date at all,
     * which is honest, where "now" would put an eight-year-old chapter at the top of Updates.
     */
    private fun String.toEpochMillis(): Long =
        runCatching { CHAPTER_DATE_FORMAT.parse(trim())?.time ?: 0L }.getOrDefault(0L)

    private companion object {
        private val GENRE_NAMES = arrayOf("Bất kỳ", "Action", "Adapted to Anime", "Adapted to Drama CD", "Adapted to Manga", "Adapted to Manhua", "Adapted to Manhwa", "Adventure", "Age Gap", "Boys Love", "Character Growth", "Chinese Novel", "Comedy", "Cooking", "Different Social Status", "Drama", "Ecchi", "English Novel", "Fanfiction", "Fantasy", "Female Protagonist", "Game", "Gender Bender", "Harem", "Historical", "Horror", "Isekai", "Josei", "Korean Novel", "Magic", "Martial Arts", "Mecha", "Military", "Misunderstanding", "Mystery", "Netorare", "Obsession", "One shot", "Otome Game", "Parody", "Psychological", "Reverse Harem", "Romance", "Satire", "School Life", "Science Fiction", "Seinen", "Shoujo", "Shoujo ai", "Shounen", "Shounen ai", "Slice of Life", "Slow Life", "Sports", "Super Power", "Supernatural", "Suspense", "Tragedy", "Wars", "Web Novel", "Workplace", "Wuxia", "Xianxia", "Yandere", "Yuri")
        private val GENRE_SLUGS = listOf("", "action", "adapted-to-anime", "adapted-to-drama-cd", "adapted-to-manga", "adapted-to-manhua", "adapted-to-manhwa", "adventure", "age-gap", "boys-love", "character-growth", "chinese-novel", "comedy", "cooking", "different-social-status", "drama", "ecchi", "english-novel", "fanfiction", "fantasy", "female-protagonist", "game", "gender-bender", "harem", "historical", "horror", "isekai", "josei", "korean-novel", "magic", "martial-arts", "mecha", "military", "misunderstanding", "mystery", "netorare", "obsession", "one-shot", "otome-game", "parody", "psychological", "reverse-harem", "romance", "satire", "school-life", "science-fiction", "seinen", "shoujo", "shoujo-ai", "shounen", "shounen-ai", "slice-of-life", "slow-life", "sports", "super-power", "supernatural", "suspense", "tragedy", "wars", "web-novel", "workplace", "wuxia", "xianxia", "yandere", "yuri")
        private const val CHUNK_PREFIX_LENGTH = 4
        private val CHAPTER_DATE_FORMAT =
            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
        private val CHAPTER_NUMBER_REGEX =
            Regex("""(?:chương|chuong)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    }

    // ============================== Chapter text ==============================

    /**
     * Mirrors DocLN's public page decoder: restore chunk order, Base64-decode each payload, then
     * XOR its bytes with the repeating key before extracting paragraphs from the recovered HTML.
     */
    override suspend fun getChapterText(chapter: SChapter): String {
        val document = client.newCall(GET(baseUrl + chapter.url, headers)).awaitSuccess().asJsoup()
        val protected = document.selectFirst("#chapter-c-protected")
            ?: throw IllegalStateException("DocLN chapter payload is missing")
        val scheme = protected.attr("data-s")
        val key = protected.attr("data-k")
        val payload = protected.attr("data-c")

        require(scheme == "xor_shuffle") { "Unsupported DocLN chapter scheme: $scheme" }
        require(key.isNotEmpty()) { "DocLN chapter key is missing" }

        val chunks = try {
            Json.parseToJsonElement(payload).jsonArray.map { it.jsonPrimitive.content }
        } catch (error: Exception) {
            throw IllegalStateException("DocLN chapter payload is malformed", error)
        }
        require(chunks.isNotEmpty()) { "DocLN chapter payload is empty" }

        val decodedHtml = chunks
            .sortedBy { chunk ->
                chunk.take(CHUNK_PREFIX_LENGTH).toIntOrNull()
                    ?: throw IllegalStateException("DocLN chapter chunk order is malformed")
            }
            .joinToString(separator = "") { chunk ->
                require(chunk.length > CHUNK_PREFIX_LENGTH) {
                    "DocLN chapter chunk is malformed"
                }
                val encrypted = Base64.getDecoder().decode(chunk.substring(CHUNK_PREFIX_LENGTH))
                val decoded = ByteArray(encrypted.size) { index ->
                    (encrypted[index].toInt() xor key[index % key.length].code).toByte()
                }
                decoded.toString(Charsets.UTF_8)
            }

        // Parse with baseUrl as the base URI so any relative image source resolves to an absolute URL.
        val body = Jsoup.parseBodyFragment(decodedHtml, baseUrl).body()
        NovelChapterHtml.stripHiddenContent(body)
        // Illustration-only chapters are legitimate, so images alone are enough to render.
        return NovelChapterHtml.toBlocks(body)
            .joinToString("\n\n")
            .takeIf(String::isNotEmpty)
            ?: throw IllegalStateException("DocLN chapter text is empty")
    }

    /** The site pads text with non-breaking spaces; normalise so descriptions wrap properly. */
    private fun String.cleanText(): String = replace(' ', ' ').trim()
}
