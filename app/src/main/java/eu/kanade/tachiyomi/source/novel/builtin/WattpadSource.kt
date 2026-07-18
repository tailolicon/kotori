package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/**
 * Built-in novel source for Wattpad (wattpad.com), read natively through Wattpad's own JSON API —
 * the same endpoints its web reader uses — rather than a WebView.
 *
 * A Wattpad "story" is a novel and its "parts" are chapters. Public stories need no login; Paid
 * Stories and login-gated (mature) chapters return no text and are simply skipped. Long parts are
 * paginated, so [getChapterText] walks pages until one comes back empty.
 */
class WattpadSource : BuiltInNovelSource() {

    override val name = "Wattpad"
    override val lang = "all"
    override val baseUrl = "https://www.wattpad.com"
    override val supportsLatest = true
    override val iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=wattpad.com"

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", "$baseUrl/")

    // ============================== Browse ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = browse("hot", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse("new", page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return browse("hot", page)
        val url = "$baseUrl/v4/search/stories".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("fields", LIST_FIELDS)
            .build()
        return storiesPage(url.toString(), page)
    }

    private suspend fun browse(filter: String, page: Int): MangasPage {
        val url = "$baseUrl/api/v3/stories".toHttpUrl().newBuilder()
            .addQueryParameter("filter", filter)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("fields", LIST_FIELDS)
            .build()
        return storiesPage(url.toString(), page)
    }

    private suspend fun storiesPage(url: String, page: Int): MangasPage {
        val body = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val stories = root["stories"]?.jsonArray.orEmpty()
        val entries = stories.map { it.jsonObject.toSManga() }
        // "hot"/"new" don't report a total, so assume more while a full page keeps coming back.
        val hasNext = entries.size >= PAGE_SIZE
        return MangasPage(entries, hasNext)
    }

    private fun JsonObject.toSManga(): SManga = SManga.create().apply {
        val id = get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        url = "/story/$id"
        title = get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
        thumbnail_url = get("cover")?.jsonPrimitive?.contentOrNull
        author = get("user")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
    }

    // ============================== Details ==============================

    override suspend fun getNovelDetails(novel: SManga): SManga {
        val id = novel.storyId()
        val url = "$baseUrl/api/v3/stories/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", DETAIL_FIELDS)
            .build()
        val body = client.newCall(GET(url.toString(), headers)).awaitSuccess().use { it.body.string() }
        val story = json.parseToJsonElement(body).jsonObject
        return SManga.create().apply {
            this.url = novel.url
            title = story["title"]?.jsonPrimitive?.contentOrNull ?: novel.title
            thumbnail_url = story["cover"]?.jsonPrimitive?.contentOrNull ?: novel.thumbnail_url
            author = story["user"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            genre = story["tags"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            description = story["description"]?.jsonPrimitive?.contentOrNull
            status = when (story["completed"]?.jsonPrimitive?.contentOrNull) {
                "true" -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            initialized = true
        }
    }

    // ============================== Chapters ==============================

    override suspend fun getChapterList(novel: SManga): List<SChapter> {
        val id = novel.storyId()
        val url = "$baseUrl/api/v3/stories/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "parts(id,title,url,deleted,draft)")
            .build()
        val body = client.newCall(GET(url.toString(), headers)).awaitSuccess().use { it.body.string() }
        val parts = json.parseToJsonElement(body).jsonObject["parts"]?.jsonArray.orEmpty()

        return parts
            .map { it.jsonObject }
            .filterNot { it["deleted"]?.jsonPrimitive?.contentOrNull == "true" }
            .filterNot { it["draft"]?.jsonPrimitive?.contentOrNull == "true" }
            .mapIndexed { index, part ->
                SChapter.create().apply {
                    val partId = part["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    this.url = "/part/$partId"
                    name = part["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    chapter_number = (index + 1).toFloat()
                }
            }
            // Site lists oldest-first; the app expects newest-first.
            .reversed()
    }

    // ============================== Chapter text ==============================

    override suspend fun getChapterText(chapter: SChapter): String {
        val partId = chapter.url.substringAfterLast('/')
        val builder = StringBuilder()
        var page = 1
        while (page <= MAX_TEXT_PAGES) {
            val url = "$baseUrl/apiv2/".toHttpUrl().newBuilder()
                .addQueryParameter("m", "storytext")
                .addQueryParameter("id", partId)
                .addQueryParameter("page", page.toString())
                .build()
            val html = client.newCall(GET(url.toString(), headers)).awaitSuccess().use { it.body.string() }
            if (html.isBlank()) break

            val paragraphs = Jsoup.parseBodyFragment(html).select("p")
                .map { it.wholeText().trim() }
                .filter { it.isNotEmpty() }
            if (paragraphs.isEmpty()) break

            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(paragraphs.joinToString("\n\n"))
            page++
        }
        return builder.toString().ifBlank {
            "Không đọc được nội dung chương (có thể cần đăng nhập hoặc là chương trả phí)."
        }
    }

    private fun SManga.storyId(): String = STORY_ID_REGEX.find(url)?.value.orEmpty()

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_TEXT_PAGES = 30
        private const val LIST_FIELDS = "stories(id,title,cover,user(name),numParts,completed)"
        private const val DETAIL_FIELDS =
            "id,title,cover,description,completed,numParts,user(name),tags"
        private val STORY_ID_REGEX = Regex("""\d+""")
    }
}
