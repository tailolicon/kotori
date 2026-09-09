package app.kotori.extension.vi.novelfever

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.novel.NovelChapterHtml
import eu.kanade.tachiyomi.source.online.MirroredNovelSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
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
 * similarly named MeTruyenChu website, so generic mixing of those two catalogues produces
 * plausible-looking but incorrect chapter lists. One explicitly tagged archive entry is routed to
 * [TruyenNetArchive] because the current API dropped Chấp Ma while readers still expect it here.
 */
class NovelFever : MirroredNovelSource() {

    override val name = "Novel Fever"

    // Keep libraries created by the old, incorrectly labelled source attached after renaming it.
    override val id = generateId(LEGACY_SOURCE_NAME, lang, versionId)

    override val baseUrl = "https://android.lonoapp.net/api"
    override val supportsLatest = true
    private val json = Json { ignoreUnknownKeys = true }
    private val bookIdsBySlug = ConcurrentHashMap<String, String>()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Accept", "application/json")
        .set("Referer", "https://android.lonoapp.net/")

    override fun getMangaUrl(manga: SManga): String =
        if (TruyenNetArchive.isNovelUrl(manga.url)) {
            TruyenNetArchive.siteUrl(manga.url)
        } else {
            super.getMangaUrl(manga)
        }

    override fun getChapterUrl(chapter: SChapter): String =
        if (TruyenNetArchive.isChapterUrl(chapter.url)) {
            TruyenNetArchive.siteUrl(chapter.url)
        } else {
            super.getChapterUrl(chapter)
        }

    // ============================== Browse ==============================

    /**
     * `view_count` is 0 on every single book in the catalogue, so sorting by it asks the server to
     * order 397 rows on a key they all share. Its paging then repeats one book and drops another —
     * measured, and byte-identical across runs, so it is the tie rather than flakiness. `vote_count`
     * is the only popularity signal the API actually carries (213 distinct values, max 309298) and
     * a full walk on it returns every book exactly once.
     */
    override suspend fun getPopularManga(page: Int): MangasPage =
        books(page = page, sort = "-vote_count").toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        books(page = page, sort = "-new_chap_at").toMangasPage()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Bỏ trống ô tìm kiếm để duyệt theo thể loại"),
        GenreFilter(),
    )

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val genreId = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedId()
        val term = query.trim()
        if (term.isEmpty()) {
            return books(page = page, genreId = genreId, sort = "-new_chap_at").toMangasPage()
        }

        // Paging only asks for page 2 once page 1 has arrived, so remembering which of the two
        // searches answered this query keeps the rest of the pages coming from the same place
        // instead of silently switching source halfway down the grid.
        val key = NovelFeverSearch.normalize(term)
        val cached = cachedSearch
        if (page > 1 && cached != null && cached.first == key) return cached.second.asPage(page)

        val remote = books(page = page, query = term, genreId = genreId)
        if (remote.books.isNotEmpty()) {
            if (page == 1) cachedSearch = null
            return remote.toMangasPage()
        }
        // Past page 1 the server has already told us where the results end; only the first page
        // coming back empty means the query itself went unplaced.
        if (page > 1) return MangasPage(emptyList(), false)
        // A genre is tapped, not typed, and the local index carries no genres — falling back here
        // would quietly drop the genre the reader chose.
        if (genreId != null) return MangasPage(emptyList(), false)

        if (TruyenNetArchive.matches(term)) {
            val hits = listOf(TruyenNetArchive.searchResult())
            cachedSearch = key to hits
            return hits.asPage(1)
        }

        val hits = searchCatalogue(term)
        cachedSearch = key to hits
        return hits.asPage(1)
    }

    private suspend fun books(
        page: Int,
        query: String? = null,
        genreId: String? = null,
        sort: String? = null,
        limit: Int = PAGE_SIZE,
    ): BookPage {
        val parameters = buildList {
            add("include" to "author,creator,genres,tags")
            add("limit" to limit.toString())
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
            (pagination == null && books.size >= limit)
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
        author = authorName()
    }

    // ============================== Local search ==============================

    /**
     * The whole catalogue, kept so [NovelFeverSearch] can answer a query the server could not.
     *
     * It is small enough to be worth holding — a few hundred books, four requests, well under a
     * megabyte gzipped — and the server still answers first, since that is one request and it
     * paginates. This is built only once a search comes back with nothing at all.
     */
    private class Catalogue(
        val mangas: List<SManga>,
        val index: List<NovelFeverSearch.Entry>,
        val fetchedAt: Long,
    )

    private val catalogueLock = Mutex()

    @Volatile
    private var catalogue: Catalogue? = null

    /** The last query answered locally, so its later pages are neither re-matched nor re-fetched. */
    @Volatile
    private var cachedSearch: Pair<String, List<SManga>>? = null

    private suspend fun searchCatalogue(query: String): List<SManga> {
        val loaded = loadCatalogue()
        return NovelFeverSearch.match(loaded.index, query).map(loaded.mangas::get)
    }

    private suspend fun loadCatalogue(): Catalogue {
        catalogue?.takeIf { it.isFresh() }?.let { return it }
        return catalogueLock.withLock {
            // Two searches can miss at once; the second waits here and then finds it already built.
            catalogue?.takeIf { it.isFresh() }?.let { return@withLock it }

            val collected = mutableListOf<JsonObject>()
            var page = 1
            while (page <= MAX_CATALOGUE_PAGES) {
                val result = books(page = page, limit = CATALOGUE_PAGE_SIZE)
                collected += result.books
                if (result.books.isEmpty() || !result.hasNext) break
                page++
            }
            val mangas = collected.map { it.toSManga() }
            Catalogue(
                mangas = mangas,
                index = mangas.map { NovelFeverSearch.Entry(it.title) },
                fetchedAt = System.currentTimeMillis(),
            ).also { catalogue = it }
        }
    }

    private fun Catalogue.isFresh(): Boolean =
        mangas.isNotEmpty() && System.currentTimeMillis() - fetchedAt < CATALOGUE_TTL_MS

    private fun List<SManga>.asPage(page: Int): MangasPage {
        val from = (page - 1) * PAGE_SIZE
        if (from >= size) return MangasPage(emptyList(), false)
        val to = minOf(from + PAGE_SIZE, size)
        return MangasPage(subList(from, to).toList(), to < size)
    }

    // ============================== Details ==============================

    override suspend fun getNovelDetails(novel: SManga): SManga {
        if (TruyenNetArchive.isNovelUrl(novel.url)) {
            val html = archiveGet(TruyenNetArchive.siteUrl(novel.url))
            return TruyenNetArchive.parseDetails(html, novel).novel
        }

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
            author = book.authorName()
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
        if (TruyenNetArchive.isNovelUrl(novel.url)) return getArchivedChapterList(novel)

        val bookId = novel.bookId()
        val root = api("/chapters", listOf("filter[book_id]" to bookId))
        val chapters = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { chapter ->
                SChapter.create().apply {
                    val chapterId = chapter.string("id").orEmpty()
                    url = "/chapters/$chapterId"
                    // The list endpoint leaves is_locked null on every row and prices the chapter
                    // in unlock_price instead, so that is the only field worth reading here.
                    val priced = (chapter.string("unlock_price")?.toIntOrNull() ?: 0) > 0
                    name = chapter.string("name")?.trim().orEmpty()
                        .let { if (priced) "🔒 $it" else it }
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
        if (TruyenNetArchive.isChapterUrl(chapter.url)) {
            val html = archiveGet(TruyenNetArchive.siteUrl(chapter.url))
            return TruyenNetArchive.parseChapterText(html)
        }

        val chapterId = CHAPTER_ID_REGEX.find(chapter.url)?.value
            ?: throw IllegalStateException("URL chương Novel Fever không hợp lệ: ${chapter.url}")
        val data = api("/chapters/$chapterId")["data"] as? JsonObject
            ?: throw IllegalStateException("Novel Fever không trả về chương $chapterId")

        // A chapter behind the site's coin wall still answers 200 with a `content` that decrypts
        // cleanly — into the first two lines and nothing else. Rendering that silently is the worst
        // outcome available: the reader sees a paragraph, the progress tracker marks the chapter
        // read because a teaser fits one screen, and the downloader writes the stub to disk and
        // flags it DOWNLOADED for good. There is no login here to unlock it with, so say so.
        val locked = data.string("is_locked") == "1" ||
            (data.string("unlock_price")?.toIntOrNull() ?: 0) > 0
        if (locked) {
            throw IllegalStateException(
                "Chương này bị khoá trên Novel Fever (phải mở bằng xu trên ứng dụng gốc)",
            )
        }

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
        // The decrypted payload is the chapter's HTML, so run it through the shared extractor: that
        // keeps prose and inline illustrations in document order instead of rendering raw markup.
        val body = Jsoup.parseBodyFragment(plaintext, baseUrl).body()
        NovelChapterHtml.stripHiddenContent(body)
        return NovelChapterHtml.toBlocks(body)
            .joinToString("\n\n")
            .takeIf(String::isNotEmpty)
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

    private suspend fun getArchivedChapterList(novel: SManga): List<SChapter> {
        val detailsHtml = archiveGet(TruyenNetArchive.siteUrl(novel.url))
        val details = TruyenNetArchive.parseDetails(detailsHtml, novel)
        val chapters = TruyenNetArchive.parseChapters(detailsHtml).toMutableList()

        for (page in 2..details.lastPage) {
            val response = archiveGet(TruyenNetArchive.chapterPageUrl(details.bookId, page))
            val root = try {
                json.parseToJsonElement(response) as? JsonObject
            } catch (error: Exception) {
                throw IllegalStateException("Danh sách chương Chấp Ma không phải JSON hợp lệ", error)
            } ?: throw IllegalStateException("Danh sách chương Chấp Ma không hợp lệ")
            val fragment = root.string("data")
                ?: throw IllegalStateException("Danh sách chương Chấp Ma thiếu dữ liệu trang $page")
            chapters += TruyenNetArchive.parseChapters(fragment)
        }

        return chapters.distinctBy(SChapter::url).reversed()
    }

    private suspend fun archiveGet(url: String): String {
        val archiveHeaders = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/json")
            .set("Referer", "${TruyenNetArchive.BASE_URL}/")
            .build()
        return client.newCall(GET(url, archiveHeaders)).awaitSuccess().use { it.body.string() }
    }

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

    /**
     * Every book carries all four sizes, and the same url backs the grid tile, the details header
     * and the full-screen cover dialog — so the largest is the one to take. "600" is preferred over
     * "default" although they are byte-identical today: it is the explicitly sized key, and stays
     * right if the API ever repoints "default".
     */
    private fun JsonObject.posterUrl(): String? {
        val poster = get("poster") as? JsonObject ?: return null
        return poster.string("600") ?: poster.string("default")
            ?: poster.string("300") ?: poster.string("150")
    }

    /**
     * The writer's name, or null when the API has none — which is every book.
     *
     * `author` is a dead entity here: across the whole catalogue it is null on 266 books, an empty
     * name on 112, and on the remaining 19 the literal placeholder "Đang Cập Nhật" ("being
     * updated"). Letting that through put a placeholder on the details screen as if it were a
     * person, and the details screen makes an author tappable — into a search for that exact
     * string, which then matched those same 19 unrelated books and nothing else.
     *
     * `creator` is not a substitute: it is a User object with an avatar and an exp level, 333
     * distinct values with handles like "lllOUZOlll", i.e. the account that uploaded the book.
     */
    private fun JsonObject.authorName(): String? = nestedName("author")
        ?.takeIf { it.isNotBlank() && !it.equals(AUTHOR_PLACEHOLDER, ignoreCase = true) }

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
        private const val AUTHOR_PLACEHOLDER = "Đang Cập Nhật"
        private const val PAGE_SIZE = 20
        private const val MAX_LEGACY_SEARCH_PAGES = 3
        private const val CATALOGUE_PAGE_SIZE = 100
        private const val MAX_CATALOGUE_PAGES = 20
        private const val CATALOGUE_TTL_MS = 6L * 60 * 60 * 1000
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
