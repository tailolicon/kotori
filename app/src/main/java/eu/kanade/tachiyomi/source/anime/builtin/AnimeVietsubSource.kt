package eu.kanade.tachiyomi.source.anime.builtin

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
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
class AnimeVietsubSource : BuiltInHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeVietsub"
    override val supportsLatest = true

    private val preferences by lazy { getSourcePreferences() }

    // Reads the user's domain override, so a domain change is fixable in-app without a rebuild.
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, DEFAULT_BASE_URL)
            ?.trim()?.trimEnd('/')?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL

    override val iconUrl: String
        get() = runCatching { "https://www.google.com/s2/favicons?domain=${baseUrl.toHttpUrl().host}&sz=128" }
            .getOrDefault("https://www.google.com/s2/favicons?domain=animevietsub.meme&sz=128")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Ghi đè tên miền"
            summary = "Nếu web đổi tên miền, dán địa chỉ mới vào đây (vd: $DEFAULT_BASE_URL). Để trống = mặc định. Khởi động lại app sau khi đổi."
            dialogTitle = "Tên miền mới"
            setDefaultValue(DEFAULT_BASE_URL)
        }.also(screen::addPreference)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun headersBuilder() = Headers.Builder()
        // Match the CloudflareInterceptor's UA so its cf_clearance cookie applies.
        .add("User-Agent", network.defaultUserAgentProvider())
        .add("Referer", "$baseUrl/")

    // ============================== Browse ==============================

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl("/danh-sach/list-tron-bo/", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl("/danh-sach/list-dang-chieu/", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET(listUrl("/tim-kiem/$q/", page), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response.asJsoup())

    private fun listUrl(path: String, page: Int): String {
        val base = baseUrl + path
        return if (page <= 1) base else base.trimEnd('/') + "/trang-$page.html"
    }

    private fun parseAnimeList(doc: Document): AnimesPage {
        val animes = doc.select("li.TPostMv, .TPostMv, .MovieList .TPost").mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = el.selectFirst(".Title")?.text().orEmpty().ifBlank { link.attr("title") }
                thumbnail_url = el.selectFirst("img")?.imageUrl()
            }
        }
        val hasNext = doc.selectFirst(".wp-pagenavi a.nextpostslink, .wp-pagenavi .current + a") != null ||
            animes.size >= 20
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
            info["Season"]?.let { "📅 $it" },
            info["Trạng thái"]?.let { "🔴 $it" },
            info["Thời lượng"]?.let { "🎬 $it" },
            info["Studio"]?.let { "🎞️ $it" },
            info["Ngôn ngữ"]?.let { "🗣️ $it" },
        ).joinToString("  •  ")
        if (meta.isNotBlank()) appendLine(meta)

        doc.selectFirst(".name, .SubTitle, .other_name")?.text()?.takeIf { it.isNotBlank() }?.let {
            appendLine("Tên khác: $it")
        }

        val synopsis = doc.selectFirst(".Description, div[itemprop=description], .film-content, .content-info, .mvic-desc")
            ?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        if (!synopsis.isNullOrBlank()) {
            appendLine()
            append(synopsis.trim())
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
        const val DEFAULT_BASE_URL = "https://animevietsub.meme"
        private const val PREF_DOMAIN_KEY = "override_base_url"

        private val M3U8_REGEX = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
    }
}
