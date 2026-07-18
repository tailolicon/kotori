package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native source for the Novel Fever Android catalogue.
 *
 * Novel Fever's book and chapter identities come from its JSON API. They are unrelated to the
 * similarly named MeTruyenChu website, so mixing those two catalogues produces plausible-looking
 * but incorrect chapter lists. All operations in this source intentionally stay within one API.
 */
class NovelFeverSource : BuiltInNovelSource() {

    override val name = "Novel Fever"

    // Keep libraries created by the old, incorrectly labelled source attached after renaming it.
    override val id = generateId(LEGACY_SOURCE_NAME, lang, versionId)

    override val baseUrl = "https://android.lonoapp.net/api"
    override val supportsLatest = true
    override val iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=android.lonoapp.net"

    private val json = Json { ignoreUnknownKeys = true }
    private val bookIdsBySlug = ConcurrentHashMap<String, String>()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Accept", "application/json")
        .set("Referer", "https://android.lonoapp.net/")

    // ============================== Browse ==============================

    override suspend fun getPopularManga(page: Int): MangasPage =
        books(page = page, sort = "-view_count").toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        books(page = page, sort = "-new_chap_at").toMangasPage()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Bỏ trống ô tìm kiếm để duyệt theo thể loại"),
        GenreFilter(),
    )

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val genreId = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedId()
        return books(
            page = page,
            query = query.trim().takeIf(String::isNotEmpty),
            genreId = genreId,
            sort = if (query.isBlank()) "-new_chap_at" else null,
        ).toMangasPage()
    }

    private suspend fun books(
        page: Int,
        query: String? = null,
        genreId: String? = null,
        sort: String? = null,
    ): BookPage {
        val parameters = buildList {
            add("include" to "author,creator,genres,tags")
            add("limit" to PAGE_SIZE.toString())
            add("page" to page.toString())
            query?.let { add("filter[keyword]" to it) }
            genreId?.let { add("filter[genres.id]" to it) }
            sort?.let { add("sort" to it) }
        }
        val root = api("/books", parameters)
        val books = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        val pagination = root["pagination"] as? JsonObject
        val hasNext = pagination?.string("next") != null ||
            page < (pagination?.string("last")?.toIntOrNull() ?: page) ||
            (pagination == null && books.size >= PAGE_SIZE)
        return BookPage(books, hasNext)
    }

    private fun BookPage.toMangasPage(): MangasPage =
        MangasPage(books.map { it.toSManga() }, hasNext)

    private fun JsonObject.toSManga(): SManga = SManga.create().apply {
        val bookId = string("id").orEmpty()
        val slug = string("slug").orEmpty()
        if (bookId.isNotEmpty() && slug.isNotEmpty()) bookIdsBySlug[slug] = bookId

        url = "/books/$bookId"
        title = string("name").orEmpty()
        thumbnail_url = posterUrl()
        author = nestedName("author")
    }

    // ============================== Details ==============================

    override suspend fun getNovelDetails(novel: SManga): SManga {
        val bookId = novel.bookId()
        val book = api("/books/$bookId")["data"] as? JsonObject
            ?: throw IllegalStateException("Novel Fever không trả về thông tin truyện $bookId")

        book.string("slug")?.let { bookIdsBySlug[it] = bookId }
        return SManga.create().apply {
            // Retain a legacy URL if this library entry still uses one; changing it here would make
            // the database treat the same title as a different entry.
            url = novel.url
            title = book.string("name") ?: novel.title
            thumbnail_url = book.posterUrl() ?: novel.thumbnail_url
            author = book.nestedName("author")
            genre = (book["genres"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name") }
                .joinToString()
            description = book.string("synopsis")?.normalizeSynopsis()
            status = when (book.string("status")?.toIntOrNull()) {
                1 -> SManga.ONGOING
                2 -> SManga.COMPLETED
                3 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Chapters ==============================

    override suspend fun getChapterList(novel: SManga): List<SChapter> {
        val bookId = novel.bookId()
        val root = api("/chapters", listOf("filter[book_id]" to bookId))
        val chapters = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { chapter ->
                SChapter.create().apply {
                    val chapterId = chapter.string("id").orEmpty()
                    url = "/chapters/$chapterId"
                    name = chapter.string("name")?.trim().orEmpty()
                    // The API index is the reading order. Displayed chapter numbers may deliberately
                    // differ, as in list index 604 being named "Chương 606".
                    chapter_number = chapter.string("index")?.toFloatOrNull() ?: -1f
                    date_upload = chapter.string("published_at").toEpochMillis()
                }
            }

        // Novel Fever returns oldest-first; Kotori's chapter stack expects newest-first.
        return chapters.reversed()
    }

    // ============================== Chapter text ==============================

    override suspend fun getChapterText(chapter: SChapter): String {
        val chapterId = CHAPTER_ID_REGEX.find(chapter.url)?.value
            ?: throw IllegalStateException("URL chương Novel Fever không hợp lệ: ${chapter.url}")
        val data = api("/chapters/$chapterId")["data"] as? JsonObject
            ?: throw IllegalStateException("Novel Fever không trả về chương $chapterId")
        val encrypted = data.string("content")
            ?: throw IllegalStateException("Chương Novel Fever $chapterId không có nội dung")
        return decryptChapter(encrypted)
    }

    /**
     * Mirrors Novel Fever 1.4.4's reader protocol:
     * 1. the 16 characters at offsets 17..33 are the AES key;
     * 2. remove that inserted key and Base64-decode a JSON `{iv,value}` envelope;
     * 3. decrypt `value` with AES-CBC and PKCS padding.
     */
    private fun decryptChapter(encrypted: String): String {
        require(encrypted.length >= KEY_END) { "Nội dung chương Novel Fever bị cắt cụt" }

        val key = encrypted.substring(KEY_START, KEY_END)
        val envelope = try {
            val encoded = encrypted.replace(key, "")
            val decoded = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
            json.parseToJsonElement(decoded) as? JsonObject
        } catch (error: Exception) {
            throw IllegalStateException("Không giải mã được phong bì chương Novel Fever", error)
        } ?: throw IllegalStateException("Phong bì chương Novel Fever không hợp lệ")

        val iv = envelope.string("iv")
            ?: throw IllegalStateException("Chương Novel Fever thiếu IV")
        val value = envelope.string("value")
            ?: throw IllegalStateException("Chương Novel Fever thiếu dữ liệu mã hóa")

        val plaintext = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(Base64.getDecoder().decode(iv)),
            )
            cipher.doFinal(Base64.getDecoder().decode(value)).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("Không giải mã được nội dung chương Novel Fever", error)
        }
        return plaintext.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalStateException("Nội dung chương Novel Fever trống")
    }

    // ============================== Legacy URLs ==============================

    private suspend fun SManga.bookId(): String {
        BOOK_ID_REGEX.find(url)?.groupValues?.get(1)?.let { return it }

        val slug = url.trim('/').substringAfterLast('/').lowercase()
        LEGACY_BOOK_IDS[slug]?.let { return it }
        bookIdsBySlug[slug]?.let { return it }

        // Most old entries can be repaired from their stored title. Validate the returned slug so
        // a fuzzy API result can never silently attach the library entry to the wrong novel.
        val searchTerm = title.trim().ifEmpty { slug.replace('-', ' ') }
        var page = 1
        while (page <= MAX_LEGACY_SEARCH_PAGES) {
            val result = books(page = page, query = searchTerm)
            result.books.firstOrNull { it.string("slug")?.lowercase() == slug }
                ?.string("id")
                ?.let {
                    bookIdsBySlug[slug] = it
                    return it
                }
            if (!result.hasNext) break
            page++
        }
        throw IllegalStateException(
            "Không thể nối mục thư viện cũ '$title' với ID Novel Fever. " +
                "Hãy tìm lại truyện trong nguồn Novel Fever và thêm lại vào thư viện.",
        )
    }

    // ============================== HTTP / JSON ==============================

    private suspend fun api(
        path: String,
        parameters: List<Pair<String, String>> = emptyList(),
    ): JsonObject {
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            parameters.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val body = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (error: Exception) {
            throw IllegalStateException("Phản hồi Novel Fever không phải JSON hợp lệ", error)
        } ?: throw IllegalStateException("Phản hồi Novel Fever không hợp lệ")

        if (root["success"]?.jsonPrimitive?.booleanOrNull == false) {
            throw IllegalStateException(root.string("message") ?: "Novel Fever từ chối yêu cầu")
        }
        return root
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun String.normalizeSynopsis(): String =
        lineSequence()
            .joinToString("\n") { line -> line.trim(' ', '\t', '\r') }
            .trim()

    private fun JsonObject.nestedName(key: String): String? =
        (get(key) as? JsonObject)?.string("name")

    private fun JsonObject.posterUrl(): String? {
        val poster = get("poster") as? JsonObject ?: return null
        return poster.string("300") ?: poster.string("600") ?: poster.string("default")
    }

    private fun String?.toEpochMillis(): Long = this?.let { value ->
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    } ?: 0L

    private class GenreFilter : Filter.Select<String>(
        "Thể loại",
        GENRES.map { it.second }.toTypedArray(),
    ) {
        fun selectedId(): String? = GENRES.getOrNull(state)?.first?.takeIf(String::isNotEmpty)
    }

    private data class BookPage(
        val books: List<JsonObject>,
        val hasNext: Boolean,
    )

    private companion object {
        private const val LEGACY_SOURCE_NAME = "Nôvel Fever (MeTruyenChu)"
        private const val PAGE_SIZE = 20
        private const val MAX_LEGACY_SEARCH_PAGES = 3
        private const val KEY_START = 17
        private const val KEY_END = 33

        private val BOOK_ID_REGEX = Regex("""^/books/(\d+)(?:/.*)?$""")
        private val CHAPTER_ID_REGEX = Regex("""\d+""")

        /**
         * API search only indexes the current catalogue, while direct detail endpoints retain some
         * older books. Keep exact, verified IDs for legacy library entries reported in the field.
         */
        private val LEGACY_BOOK_IDS = mapOf(
            "xuyen-thu-thanh-phan-phai-nu-chu-nhom-nhan-thiet-tan-vo" to "111548",
        )

        private val GENRES = listOf(
            "" to "Bất kỳ",
            "2" to "Tiên Hiệp",
            "3" to "Huyền Huyễn",
            "4" to "Khoa Huyễn",
            "5" to "Võng Du",
            "6" to "Đô Thị",
            "7" to "Đồng Nhân",
            "8" to "Dã Sử",
            "9" to "Cạnh Kỹ",
            "10" to "Hiện Đại Ngôn Tình",
            "11" to "Huyền Nghi",
            "12" to "Kiếm Hiệp",
            "13" to "Huyền Huyễn Ngôn Tình",
            "14" to "Tiên Hiệp Kỳ Duyên",
            "15" to "Cổ Đại Ngôn Tình",
            "16" to "Huyền Nghi Thần Quái",
            "17" to "Khoa Huyễn Không Gian",
            "18" to "Lãng Mạn Thanh Xuân",
            "20" to "Kỳ Ảo",
            "22" to "Light Novel",
        )
    }
}
