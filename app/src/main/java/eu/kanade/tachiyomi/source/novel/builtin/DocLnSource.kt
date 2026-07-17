package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return listing("capnhat", page)
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("page", page.toString())
            .build()
        return client.newCall(GET(url, headers)).awaitSuccess().asJsoup().toMangasPage()
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
        private val CHAPTER_NUMBER_REGEX =
            Regex("""(?:chương|chuong)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    }

    // ============================== Chapter text ==============================

    override suspend fun getChapterText(chapter: SChapter): String {
        val document = client.newCall(GET(baseUrl + chapter.url, headers)).awaitSuccess().asJsoup()
        val content = document.selectFirst("#chapter-content")
            ?: return "Không đọc được nội dung chương."
        // Drop the site's own notes/ads anchors, keep prose only.
        content.select("a, script, style, ins, iframe").remove()
        return content.select("p")
            .map { it.wholeText().cleanText() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { content.wholeText().cleanText() }
    }

    private fun String.cleanText(): String = replace(' ', ' ').trim()
}
