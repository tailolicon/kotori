package eu.kanade.tachiyomi.source.anime.builtin

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Built-in source for animehay08.site (Vietnamese sub/dub anime).
 *
 * Video delivery: the watch page embeds a `$wp_servers` JS map pointing at an embed host
 * (e.g. ahay.stream/embed-jw/<id>); that embed page exposes a direct `master.m3u8`
 * (token + expiry) which mpv can play with the embed host as Referer.
 */
class AnimeHay08Source : BuiltInHttpSource() {

    override val name = "AnimeHay"
    override val baseUrl = "https://animehay08.site"
    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", DESKTOP_UA)
        .add("Referer", "$baseUrl/")

    // ============================== Browse ==============================

    // Popular / Latest both use the "Anime" category listing (paginated).
    override fun popularAnimeRequest(page: Int): Request = GET(listingUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET(listingUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    private fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/the-loai/anime-1.html" else "$baseUrl/the-loai/anime-1/trang-$page.html"

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val keyword = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/tim-kiem/?keyword=$keyword"
        } else {
            "$baseUrl/tim-kiem/?keyword=$keyword&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    private fun parseAnimeList(doc: Document): AnimesPage {
        val animes = doc.select("div.mc").mapNotNull { el ->
            val link = el.selectFirst("a.mc__link") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.attr("title").ifBlank {
                    el.selectFirst(".mc__name")?.text().orEmpty()
                }
                thumbnail_url = el.selectFirst(".mc__poster img, img")?.imageUrl()
            }
        }
        // The category/search grids return ~30 items per full page; a short page is the last.
        return AnimesPage(animes, animes.size >= 24)
    }

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.aim-hero__title")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("#aim-poster-img, .aim-hero__poster img")?.imageUrl()
            genre = doc.select(".aim-hero__cates a.aim-cate-chip")
                .joinToString(", ") { it.text().trim() }
                .ifBlank { null }
            status = parseStatus(doc.select(".aim-hero__meta [class*=aim-status]").text())
            description = buildDescription(doc)
            initialized = true
        }
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Hoàn thành", true) || text.contains("Full", true) -> SAnime.COMPLETED
        text.contains("Đang", true) || text.contains("chiếu", true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun buildDescription(doc: Document): String = buildString {
        // Metadata line: year • status • episode count (rendered with emojis on the page).
        val meta = doc.select(".aim-hero__meta .aim-meta-item").joinToString("  •  ") { it.text().trim() }
        if (meta.isNotBlank()) appendLine(meta)

        doc.selectFirst(".aim-hero__alt-name")?.text()?.takeIf { it.isNotBlank() }?.let {
            appendLine("Tên khác: $it")
        }

        val synopsis = doc.selectFirst(".aim-desc, .aim-body .aim-description, [itemprop=description]")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        if (!synopsis.isNullOrBlank()) {
            appendLine()
            append(synopsis.trim())
        }
    }.trim()

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select(".aim-ep-grid a.aim-ep-btn").map { el ->
            val label = el.selectFirst("span")?.text()?.trim()
                ?: el.attr("title").substringAfter("Tập").trim()
            SEpisode.create().apply {
                setUrlWithoutDomain(el.attr("abs:href"))
                name = "Tập $label"
                episode_number = label.replace(",", ".").filter { it.isDigit() || it == '.' }
                    .toFloatOrNull() ?: 0f
            }
        }
    }

    // ============================== Video ==============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val watchHtml = client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess().use { it.body.string() }

        val serversBlock = SERVERS_REGEX.find(watchHtml)?.groupValues?.get(1).orEmpty()
        val embedUrls = URL_REGEX.findAll(serversBlock).map { it.value }.distinct().toList()
            .ifEmpty {
                // Fallback: any embed-looking iframe/link on the page.
                URL_REGEX.findAll(watchHtml).map { it.value }
                    .filter { it.contains("embed", true) }.distinct().toList()
            }

        val videos = mutableListOf<Video>()
        for (embed in embedUrls) {
            runCatching {
                val referer = embed.toHttpUrl().let { "${it.scheme}://${it.host}/" }
                val embedHtml = client.newCall(
                    GET(embed, headersBuilder().set("Referer", "$baseUrl/").build()),
                ).awaitSuccess().use { it.body.string() }

                M3U8_REGEX.find(embedHtml)?.value?.let { m3u8 ->
                    videos += Video(
                        videoUrl = m3u8,
                        videoTitle = "AnimeHay",
                        headers = Headers.headersOf("User-Agent", DESKTOP_UA, "Referer", referer),
                    )
                }
            }
        }
        return videos
    }

    // ============================== Helpers ==============================

    private fun Element.imageUrl(): String? {
        val raw = attr("abs:data-src").ifBlank { attr("abs:src") }.ifBlank { attr("data-src") }
            .ifBlank { attr("src") }
        return raw.ifBlank { null }
    }

    companion object {
        // var $wp_servers = { 'AHS': 'https://ahay.stream/embed-jw/76075', };
        private val SERVERS_REGEX = Regex("""${'$'}wp_servers\s*=\s*\{([^}]*)\}""")
        private val URL_REGEX = Regex("""https?://[^'"\s]+""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
    }
}
