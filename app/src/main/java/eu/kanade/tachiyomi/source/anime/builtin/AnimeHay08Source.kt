package eu.kanade.tachiyomi.source.anime.builtin

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.builtin.MirrorResolver
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
class AnimeHay08Source : BuiltInHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeHay"
    override val supportsLatest = true

    private val preferences by lazy { getSourcePreferences() }

    /**
     * Resolved rather than hardcoded: [MirrorResolver] probes the known hosts, follows
     * redirects and keeps whichever one answers, so a domain change the site itself
     * redirects is picked up without an app update. A pasted override still wins.
     */
    private val mirrors by lazy { MirrorResolver(preferences, MIRRORS) }

    override val baseUrl: String
        get() = mirrors.baseUrl(preferences.getString(PREF_DOMAIN_KEY, null))

    // Favicon follows whatever the current base domain is.
    override val iconUrl: String
        get() = runCatching { "https://www.google.com/s2/favicons?domain=${baseUrl.toHttpUrl().host}&sz=128" }
            .getOrDefault("https://www.google.com/s2/favicons?domain=animehay08.site&sz=128")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Ghi đè tên miền"
            summary = "Để trống thì app tự dò tên miền còn sống. Chỉ điền khi bạn biết " +
                "tên miền mới mà app chưa nhận ra (vd: $DEFAULT_BASE_URL)."
            dialogTitle = "Tên miền mới"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

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
        // A picked genre is its own listing page here, not a search parameter, so it replaces the
        // query instead of narrowing it.
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.slug()
        if (genre != null && query.isBlank()) {
            val genreUrl = if (page <= 1) {
                "$baseUrl/the-loai/$genre.html"
            } else {
                "$baseUrl/the-loai/${genre.substringBeforeLast('-')}/trang-$page.html"
            }
            return GET(genreUrl, headers)
        }
        val keyword = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/tim-kiem/?keyword=$keyword"
        } else {
            "$baseUrl/tim-kiem/?keyword=$keyword&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    override fun getFilterList() = AnimeFilterList(GenreFilter())

    /**
     * The site genres, as a picker.
     *
     * Exposed as a filter rather than hardcoded in the UI so the browse screen chips and the
     * filter sheet read the same list, and so picking one resolves to the site own listing page
     * rather than a text search for the genre name.
     */
    private class GenreFilter : AnimeFilter.Select<String>(
        "Thể loại",
        arrayOf("Tất cả") + GENRES.keys.toTypedArray(),
    ) {
        fun slug(): String? = values.getOrNull(state)?.takeIf { state > 0 }?.let { GENRES[it] }
    }

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
        // Year • status • episode count (the page renders these with emojis — strip them out).
        val meta = doc.select(".aim-hero__meta .aim-meta-item")
            .map { it.text().withoutEmoji() }
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        if (meta.isNotBlank()) appendLine(meta)

        doc.selectFirst(".aim-hero__alt-name")?.text()?.takeIf { it.isNotBlank() }?.let {
            appendLine("Tên khác: $it")
        }

        val synopsis = (
            doc.selectFirst(".aim-desc, .aim-body .aim-description, [itemprop=description]")?.text()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            )?.withoutEmoji()
        if (!synopsis.isNullOrBlank()) {
            appendLine()
            append(synopsis)
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

    override suspend fun resolveVideos(episode: SEpisode): List<Video> {
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
        /** The site own genre list, display name to url slug. */
        private val GENRES = linkedMapOf(
            "Hành động" to "hanh-dong-2",
            "Hài hước" to "hai-huoc-3",
            "Tình cảm" to "tinh-cam-4",
            "Harem" to "harem-5",
            "Bí ẩn" to "bi-an-6",
            "Bi kịch" to "bi-kich-7",
            "Giả tưởng" to "gia-tuong-8",
            "Học đường" to "hoc-duong-9",
            "Đời thường" to "doi-thuong-10",
            "Võ thuật" to "vo-thuat-11",
            "Trò chơi" to "tro-choi-12",
            "Thám tử" to "tham-tu-13",
            "Lịch sử" to "lich-su-14",
            "Siêu năng lực" to "sieu-nang-luc-15",
            "Shounen" to "shounen-16",
            "Shounen AI" to "shounen-ai-17",
            "Shoujo" to "shoujo-18",
            "Shoujo AI" to "shoujo-ai-19",
            "Thể thao" to "the-thao-20",
            "Âm nhạc" to "am-nhac-21",
            "Psychological" to "psychological-22",
            "Mecha" to "mecha-23",
            "Quân đội" to "quan-doi-24",
            "Drama" to "drama-25",
            "Seinen" to "seinen-26",
            "Siêu nhiên" to "sieu-nhien-27",
            "Phiêu lưu" to "phieu-luu-28",
            "Kinh dị" to "kinh-di-29",
            "Ma cà rồng" to "ma-ca-rong-30",
            "Tokusatsu" to "tokusatsu-31",
            "Samurai" to "samurai-32",
            "Viễn tưởng" to "vien-tuong-33",
            "CN Animation" to "cn-animation-34",
            "Tiên hiệp" to "tien-hiep-35",
            "Kiếm hiệp" to "kiem-hiep-36",
            "Xuyên không" to "xuyen-khong-37",
            "Trùng sinh" to "trung-sinh-38",
            "Huyền ảo" to "huyen-ao-39",
            "Ngôn tình (CNA)" to "cna-ngon-tinh-40",
            "Dị giới" to "di-gioi-41",
            "Hài hước (CNA)" to "cna-hai-huoc-42",
            "Đam mỹ" to "dam-my-43",
            "Võ hiệp" to "vo-hiep-44",
            "Ecchi" to "ecchi-45",
            "Demon" to "demon-46",
            "Live Action" to "live-action-47",
            "Thriller" to "thriller-48",
            "Khoa huyễn" to "khoa-huyen-49",
        )

        const val DEFAULT_BASE_URL = "https://animehay10.site"

        /**
         * Hosts to probe, best-known first. This is a starting point, not a fixed list:
         * whichever one answers is followed through its redirects, so the domain the app
         * settles on can be one that is not written here.
         */
        private val MIRRORS = listOf("animehay10.site", "animehay09.site", "animehay08.site")
        private const val PREF_DOMAIN_KEY = "override_base_url"

        // var $wp_servers = { 'AHS': 'https://ahay.stream/embed-jw/76075', };
        private val SERVERS_REGEX = Regex("""${'$'}wp_servers\s*=\s*\{([^}]*)\}""")
        private val URL_REGEX = Regex("""https?://[^'"\s]+""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
    }
}
