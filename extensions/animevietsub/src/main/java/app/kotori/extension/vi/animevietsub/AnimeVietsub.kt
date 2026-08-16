package app.kotori.extension.vi.animevietsub

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.HosterAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.MirrorResolver
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Built-in source for animevietsub.meme (Vietnamese-subbed anime).
 *
 * Metadata is scraped from the classic AnimeVietsub theme. Cloudflare is handled by the app's
 * shared client (it already wraps a CloudflareInterceptor), so we intentionally reuse the app's
 * default User-Agent so the cf_clearance cookie stays valid.
 *
 * Video is best-effort: the watch page carries per-episode data-id/data-hash which are POSTed to
 * `/ajax/player`; the response points at an embed (or an encrypted link array). The embed is
 * followed with the site Referer and its master playlist extracted. Some servers encrypt the link
 * client-side (Web Crypto), which this cannot resolve — those fall back to the metadata being
 * available even if a given server yields no direct stream.
 */
class AnimeVietsub : HosterAnimeSource(), ConfigurableAnimeSource {

    override val name = "AnimeVietsub"
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

    /**
     * The site answers the first request of a session with `403`, a `Set-Cookie`, and a one-line
     * script that bounces the browser back to the same URL. That is a handshake, not a block —
     * the next request carrying those cookies gets a normal `200`, which is why the site works in
     * a browser but every call from here failed. OkHttp has already stored the cookies by the
     * time an application interceptor sees the response, so replaying once is the whole fix.
     *
     * Wrapped by [MirrorResolver.install] so a blocked domain invalidates the cached answer
     * rather than failing every request until the TTL expires.
     */
    override val client: OkHttpClient by lazy {
        mirrors.install(
            network.client.newBuilder()
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code != 403 || response.headers("Set-Cookie").isEmpty()) {
                        response
                    } else {
                        response.close()
                        chain.proceed(chain.request())
                    }
                }
                .build(),
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun headersBuilder() = Headers.Builder()
        // Match the CloudflareInterceptor's UA so its cf_clearance cookie applies.
        .add("User-Agent", network.defaultUserAgentProvider())
        .add("Referer", "$baseUrl/")

    // ============================== Browse ==============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl("/danh-sach/list-tron-bo/", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl("/danh-sach/list-dang-chieu/", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // A picked genre is its own listing page here, not a search parameter, so it replaces the
        // query instead of narrowing it.
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.slug()
        if (genre != null && query.isBlank()) {
            return GET(browseUrl("/the-loai/$genre/", page), headers)
        }
        val q = URLEncoder.encode(query, "UTF-8")
        val base = "$baseUrl/tim-kiem/$q/"
        return GET(if (page <= 1) base else base + "trang-$page.html", headers)
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

    /**
     * A browse-list page.
     *
     * The empty path segments are not a typo — the site's own pagination links look like
     * `/danh-sach/list-tron-bo//////all//trang-2.html`, and they are the filter slots (genre,
     * year, status…) left blank. The tidy `…/trang-2.html` this used to build returns a page
     * with no entries at all, which is why browsing never got past the first screen.
     */
    private fun browseUrl(path: String, page: Int): String {
        val base = baseUrl + path
        return if (page <= 1) base else base.trimEnd('/') + "//////all//trang-$page.html"
    }

    private fun parseAnimeList(doc: Document): AnimesPage {
        // The two are nested — `<li class="TPostMv"><article class="TPost …">` — so a selector
        // matching both returns every entry twice. Take the outer card, and only reach for the
        // inner one on pages laid out without the list wrapper.
        val cards = doc.select("li.TPostMv").ifEmpty { doc.select(".MovieList article.TPost") }
        val animes = cards.mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = el.selectFirst(".Title")?.text().orEmpty().ifBlank { link.attr("title") }
                thumbnail_url = el.selectFirst("img")?.imageUrl()
            }
        }
        // A page number higher than the current one is the site's own "there is more" marker.
        val hasNext = doc.selectFirst(".wp-pagenavi a.page.larger, .wp-pagenavi a.nextpostslink") != null
        return AnimesPage(animes, hasNext && animes.isNotEmpty())
    }

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val info = doc.select(".mvici-left p, .mvici-right p, .InfoList li").mapNotNull { p ->
            val text = p.text().trim()
            val idx = text.indexOf(':')
            if (idx <= 0) null else text.substring(0, idx).trim() to text.substring(idx + 1).trim()
        }.toMap()

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst(".Image img, .movie-l-img img, .film-poster img, .TPostBg img")?.imageUrl()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = info["Thể loại"]?.ifBlank { null }
            author = (info["Studio"] ?: info["Đạo diễn"])?.ifBlank { null }
            status = parseStatus(info["Trạng thái"] ?: info["Tình trạng"] ?: "")
            description = buildDescription(doc, info)
            initialized = true
        }
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Full", true) || text.contains("Hoàn thành", true) || text.contains("Trọn bộ", true) ->
            SAnime.COMPLETED
        text.contains("Đang", true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun buildDescription(doc: Document, info: Map<String, String>): String = buildString {
        val meta = listOfNotNull(
            info["Season"]?.let { "Năm: $it" },
            info["Trạng thái"]?.let { "Trạng thái: $it" },
            info["Thời lượng"]?.let { "Số tập: $it" },
            info["Studio"]?.let { "Studio: $it" },
            info["Ngôn ngữ"],
        ).joinToString(" • ")
        if (meta.isNotBlank()) appendLine(meta)

        doc.selectFirst(".name, .SubTitle, .other_name")?.text()?.takeIf { it.isNotBlank() }?.let {
            appendLine("Tên khác: $it")
        }

        val synopsis = (
            doc.selectFirst(".Description, div[itemprop=description], .film-content, .content-info, .mvic-desc")?.text()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            )?.withoutEmoji()
        if (!synopsis.isNullOrBlank()) {
            appendLine()
            append(synopsis)
        }
    }.trim()

    // ============================== Episodes ==============================

    // The episode/server list lives on the watch page, not the info page.
    override fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url.trimEnd('/') + "/xem-phim.html", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("#list-server a[data-id], .list-episode a[data-id], a[data-play][data-id]").map { el ->
            val id = el.attr("data-id")
            val hash = el.attr("data-hash")
            val label = el.text().trim()
            SEpisode.create().apply {
                // Keep the real watch url and stash the ajax params in the fragment.
                setUrlWithoutDomain(el.attr("abs:href"))
                url = "$url#$id|$hash"
                name = "Tập $label"
                episode_number = label.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
            }
        }.distinctBy { it.url }
    }

    // ============================== Video (best-effort) ==============================

    override suspend fun resolveVideos(episode: SEpisode): List<Video> {
        val params = episode.url.substringAfter('#', "").split('|')
        val id = params.getOrNull(0).orEmpty()
        val hash = params.getOrNull(1).orEmpty()
        if (id.isBlank()) return emptyList()

        val ajaxHeaders = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", baseUrl + episode.url.substringBefore('#'))
            .build()
        val body = FormBody.Builder()
            .add("link", hash)
            .add("id", id)
            .add("play", "api")
            .build()

        val resp = client.newCall(POST("$baseUrl/ajax/player?v=2019a", ajaxHeaders, body))
            .awaitSuccess().use { it.body.string() }
        val root = json.parseToJsonElement(resp).jsonObject
        val playTech = root["playTech"]?.jsonPrimitive?.contentOrNull
        val linkEl = root["link"]

        val videos = mutableListOf<Video>()

        // Case 1: iframe embed -> follow with site Referer and extract the master playlist.
        if (playTech == "iframe") {
            val embed = linkEl?.jsonPrimitive?.contentOrNull.orEmpty()
            if (embed.startsWith("http")) videos += extractFromEmbed(embed)
        }

        // Case 2: link is an array of {file,label,type}; usable when the file is a plain URL.
        runCatching {
            linkEl?.jsonArray?.forEach { item ->
                val file = item.jsonObject["file"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .replace("&http", "http")
                val label = item.jsonObject["label"]?.jsonPrimitive?.contentOrNull ?: "AnimeVietsub"
                if (file.startsWith("http")) {
                    videos += Video(
                        videoUrl = file,
                        videoTitle = label,
                        headers = Headers.headersOf("User-Agent", network.defaultUserAgentProvider(), "Referer", "$baseUrl/"),
                    )
                }
            }
        }

        return videos
    }

    private suspend fun extractFromEmbed(embed: String): List<Video> = runCatching {
        val referer = "$baseUrl/"
        val embedHeaders = headersBuilder().set("Referer", referer).build()
        val html = client.newCall(GET(embed, embedHeaders)).awaitSuccess().use { it.body.string() }
        val m3u8 = M3U8_REGEX.find(html)?.value ?: return emptyList()
        val embedReferer = runCatching { embed.toHttpUrl().let { "${it.scheme}://${it.host}/" } }.getOrDefault(referer)
        listOf(
            Video(
                videoUrl = m3u8,
                videoTitle = "AnimeVietsub",
                headers = Headers.headersOf("User-Agent", network.defaultUserAgentProvider(), "Referer", embedReferer),
            ),
        )
    }.getOrDefault(emptyList())

    // ============================== Helpers ==============================

    private fun Element.imageUrl(): String? {
        val raw = attr("abs:data-cfsrc").ifBlank { attr("abs:data-src") }
            .ifBlank { attr("abs:src") }.ifBlank { attr("src") }
        return raw.ifBlank { null }
    }

    companion object {
        /** The site own genre list, display name to url slug. */
        private val GENRES = linkedMapOf(
            "Hành động" to "hanh-dong",
            "Phiêu lưu" to "phieu-luu",
            "Đam mỹ" to "dong-tinh-nam",
            "Cartoon" to "cartoon",
            "Cổ trang" to "co-trang",
            "Hài hước" to "hai-huoc",
            "Điên loạn" to "dien-loan",
            "Demons" to "demons",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Phép thuật" to "phep-thuat",
            "Trò chơi" to "tro-choi",
            "Harem" to "harem",
            "Lịch sử" to "lich-su",
            "Kinh dị" to "kinh-di",
            "Josei" to "josei",
            "Trẻ em" to "tre-em",
            "Live Action" to "live-action",
            "Ma thuật" to "ma-thuat",
            "Võ thuật" to "martial-arts",
            "Mecha" to "mecha",
            "Quân đội" to "quan-doi",
            "Âm nhạc" to "am-nhac",
            "Bí ẩn" to "mystery",
            "Parody" to "parody",
            "Cảnh sát" to "police",
            "Psychological" to "psychological",
            "Tình cảm" to "tinh-cam",
            "Samurai" to "samurai",
            "Học đường" to "truong-hoc",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shoujo Ai" to "shoujo-ai",
            "Shounen" to "shounen",
            "Shounen Ai" to "shounen-ai",
            "Đời thường" to "doi-thuong",
            "Space" to "space",
            "Thể thao" to "the-thao",
            "Siêu năng lực" to "super-power",
            "Siêu nhiên" to "sieu-nhien",
            "Hồi hộp" to "hoi-hop",
            "Thriller" to "thriller",
            "Tokusatsu" to "tokusatsu",
            "Vampire" to "vampire",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )

        const val DEFAULT_BASE_URL = "https://animevietsub.mom"

        /**
         * Hosts to probe, best-known first. This is a starting point, not a fixed list:
         * whichever one answers is followed through its redirects, so the domain the app
         * settles on can be one that is not written here.
         *
         * Unlike AnimeHay this site does redirect its old domains at the current one, so the
         * dead entries below are worth keeping — they are the trail that leads to wherever it
         * moves next, and that is why this source needs no guessing.
         */
        private val MIRRORS = listOf(
            // Where the site actually lives as of 2026-08: probed from a Vietnamese connection,
            // `.mom`, `.ing` and `.meme` no longer answer at all, and `.moe`/`.info` redirect here.
            "animevietsub.vc",
            "animevietsub.mom",
            "animevietsub.moe",
            "animevietsub.info",
            "animevietsub.ing",
            "animevietsub.meme",
        )
        private const val PREF_DOMAIN_KEY = "override_base_url"

        private val M3U8_REGEX = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
    }
}
