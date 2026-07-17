package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    override val baseUrl = "https://docln.net"
    override val supportsLatest = true
    override val iconUrl = "https://docln.net/favicon.ico"

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
        return document.select("div.chapter-name a").map { it.toSChapter() }.reversed()
    }

    private fun Element.toSChapter(): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(absUrl("href"))
        name = text().trim()
        chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    private companion object {
        private val GENRE_NAMES = arrayOf("Bất kỳ", "Action", "Adapted to Anime", "Adapted to Drama CD", "Adapted to Manga", "Adapted to Manhua", "Adapted to Manhwa", "Adventure", "Age Gap", "Boys Love", "Character Growth", "Chinese Novel", "Comedy", "Cooking", "Different Social Status", "Drama", "Ecchi", "English Novel", "Fanfiction", "Fantasy", "Female Protagonist", "Game", "Gender Bender", "Harem", "Historical", "Horror", "Isekai", "Josei", "Korean Novel", "Magic", "Martial Arts", "Mecha", "Military", "Misunderstanding", "Mystery", "Netorare", "Obsession", "One shot", "Otome Game", "Parody", "Psychological", "Reverse Harem", "Romance", "Satire", "School Life", "Science Fiction", "Seinen", "Shoujo", "Shoujo ai", "Shounen", "Shounen ai", "Slice of Life", "Slow Life", "Sports", "Super Power", "Supernatural", "Suspense", "Tragedy", "Wars", "Web Novel", "Workplace", "Wuxia", "Xianxia", "Yandere", "Yuri")
        private val GENRE_SLUGS = listOf("", "action", "adapted-to-anime", "adapted-to-drama-cd", "adapted-to-manga", "adapted-to-manhua", "adapted-to-manhwa", "adventure", "age-gap", "boys-love", "character-growth", "chinese-novel", "comedy", "cooking", "different-social-status", "drama", "ecchi", "english-novel", "fanfiction", "fantasy", "female-protagonist", "game", "gender-bender", "harem", "historical", "horror", "isekai", "josei", "korean-novel", "magic", "martial-arts", "mecha", "military", "misunderstanding", "mystery", "netorare", "obsession", "one-shot", "otome-game", "parody", "psychological", "reverse-harem", "romance", "satire", "school-life", "science-fiction", "seinen", "shoujo", "shoujo-ai", "shounen", "shounen-ai", "slice-of-life", "slow-life", "sports", "super-power", "supernatural", "suspense", "tragedy", "wars", "web-novel", "workplace", "wuxia", "xianxia", "yandere", "yuri")
        private const val CHUNK_PREFIX_LENGTH = 4
        private val CHAPTER_NUMBER_REGEX =
            Regex("""(?:chương|chuong)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        /**
         * Marks a line of [getChapterText]'s result as an illustration rather than prose: the
         * sentinel is immediately followed by the image's absolute URL. `getChapterText` returns one
         * String, so pictures have to be encoded into it; this private-use code point cannot collide
         * with real prose. The novel reader renders any line starting with it as an image.
         */
        private const val IMAGE_SENTINEL = "\uE000"

        /**
         * Illustration chapters (e.g. `c6764-minh-hoa`) carry a plain absolute `src`, but the lazy
         * attributes come first: when a page does lazy-load, `src` holds a placeholder that would
         * otherwise win over the real image.
         */
        private val IMAGE_URL_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "src")

        /**
         * Hosts an illustration may be fetched from: DocLN itself, plus the image hosts its real
         * chapters use (`c6764-minh-hoa` serves all 19 pictures from `*.bp.blogspot.com`).
         *
         * Chapter HTML is author-controlled, so an unrestricted URL would let whoever uploads a
         * chapter aim the reader at any host the device can reach — loopback, LAN, or cloud
         * link-local metadata. Restricting it to these operators also bounds redirects, since only
         * they can issue one from an allowed origin.
         */
        private val IMAGE_HOSTS = listOf("docln.net", "blogspot.com", "googleusercontent.com")
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
        body.select("script, style, [hidden], .none, .hidden").remove()
        body.getAllElements()
            .filter { it.attr("style").replace(" ", "").contains("display:none", ignoreCase = true) }
            .forEach(Element::remove)
        // Prose sits in sibling <p> blocks and illustrations in <img>; wholeText() on the body would
        // run the paragraphs together and drop the pictures entirely, so walk both in document
        // order. Jsoup lists a <p> before its own children, so text keeps its place around images.
        val nodes = body.select("p, img")
        val blocks = if (nodes.isEmpty()) {
            body.wholeText().toTextBlocks()
        } else {
            nodes.flatMap { element ->
                if (element.tagName() == "img") {
                    listOfNotNull(element.imageUrl()?.let { IMAGE_SENTINEL + it })
                } else {
                    element.wholeText().toTextBlocks()
                }
            }
        }
        // Illustration-only chapters are legitimate, so images alone are enough to render.
        return blocks
            .joinToString("\n\n")
            .takeIf(String::isNotEmpty)
            ?: throw IllegalStateException("DocLN chapter text is empty")
    }

    /** Splits a block's text into reader paragraphs, dropping any sentinel so it can never show. */
    private fun String.toTextBlocks(): List<String> = lineSequence()
        .map { it.replace(IMAGE_SENTINEL, "").cleanText() }
        .filter(String::isNotEmpty)
        .toList()

    /**
     * First trusted source among [IMAGE_URL_ATTRS]; blank, unparseable and untrusted values are
     * skipped, so an attribute holding a non-image destination simply yields no picture.
     */
    private fun Element.imageUrl(): String? = IMAGE_URL_ATTRS
        .asSequence()
        .mapNotNull { absUrl(it).toHttpUrlOrNull() }
        .firstOrNull { it.isTrustedImageHost() }
        ?.toString()

    /**
     * True for HTTPS URLs served by an [IMAGE_HOSTS] operator.
     *
     * Matching is done on the parsed [HttpUrl.host], never on the raw string: a suffix test over the
     * whole URL would accept `https://evilblogspot.com` and `https://evil.com/?x=blogspot.com`
     * alike. Requiring either an exact host or a dot-prefixed parent keeps subdomains such as
     * `1.bp.blogspot.com` working while rejecting those lookalikes. HttpUrl also punycodes IDNs, so
     * a Unicode homograph is compared in its ASCII form rather than its deceptive one.
     */
    private fun HttpUrl.isTrustedImageHost(): Boolean =
        scheme == "https" && IMAGE_HOSTS.any { host == it || host.endsWith(".$it") }

    /** The site pads text with non-breaking spaces; normalise so descriptions wrap properly. */
    private fun String.cleanText(): String = replace(' ', ' ').trim()
}
