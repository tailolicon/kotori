package app.kotori.extension.all.hitomi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Hitomi(
    override val lang: String,
    private val nozomiLang: String,
) : HttpSource() {

    override val name = "Hitomi.la"
    override val baseUrl = "https://hitomi.la"
    override val supportsLatest = true

    private val ltnUrl = "https://ltn.hitomi.la"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cachedGg: Pair<Long, HitomiGg.Script>? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    override fun popularMangaRequest(page: Int): Request =
        nozomiRequest("popular/year-$nozomiLang", page)

    override fun popularMangaParse(response: Response): MangasPage = parseNozomi(response)

    override fun latestUpdatesRequest(page: Int): Request =
        nozomiRequest("index-$nozomiLang", page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseNozomi(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.slug() ?: nozomiLang
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.slug()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.slug()
        val typedTag = filters.filterIsInstance<TagFilter>().firstOrNull()?.state?.trim().orEmpty()

        val tag = when {
            typedTag.isNotBlank() -> typedTag
            genre != null -> genre
            query.isNotBlank() -> query.trim()
            else -> null
        }

        // Tag pages on hitomi.la are the unfiltered `-all` nozomi. Using the source language
        // (vietnamese, english, …) here is what made a genre look like it only had a handful
        // of titles while the website still had thousands.
        val tagLanguage = if (tag != null) "all" else language

        val listing = when {
            tag != null -> tagListing(tag, tagLanguage)
            type != null -> "$type-$language"
            else -> "index-$language"
        }
        return nozomiRequest(listing, page)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseNozomi(response)

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$ltnUrl/galleries/${galleryId(manga.url)}.js", headers)

    override fun mangaDetailsParse(response: Response): SManga =
        galleryToManga(decodeGallery(response), listingOnly = false)

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val gallery = decodeGallery(response)
        return listOf(
            SChapter.create().apply {
                url = "/galleries/${gallery.numericId()}.html"
                name = "Gallery"
                chapter_number = 1f
                date_upload = parseDate(gallery.date)
            },
        )
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$ltnUrl/galleries/${galleryId(chapter.url)}.js", headers)

    override fun pageListParse(response: Response): List<Page> {
        val gallery = decodeGallery(response)
        val gg = loadGg()
        return gallery.files.orEmpty().mapIndexed { index, file ->
            val hash = file.hash.orEmpty()
            Page(index, imageUrl = HitomiGg.imageUrl(gg, hash, preferAvif = file.hasavif == 1))
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Unused")

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun getFilterList() = FilterList(
        LanguageFilter(nozomiLang),
        TypeFilter(),
        GenreFilter(),
        TagFilter(),
    )

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    private fun nozomiRequest(listing: String, page: Int): Request {
        val (from, to) = HitomiNozomi.byteRange(page)
        return GET(
            nozomiUrl(listing),
            headers.newBuilder().set("Range", "bytes=$from-$to").build(),
        )
    }

    private fun nozomiUrl(listing: String): HttpUrl {
        val builder = ltnUrl.toHttpUrl().newBuilder()
        val parts = listing.split('/')
        parts.dropLast(1).forEach { builder.addPathSegment(it) }
        builder.addPathSegment("${parts.last()}.nozomi")
        return builder.build()
    }

    private fun tagListing(tag: String, language: String): String {
        val namespaced = tag.trim().lowercase()
        return when {
            namespaced.startsWith("artist:") ->
                "artist/${namespaced.removePrefix("artist:")}-$language"
            namespaced.startsWith("character:") ->
                "character/${namespaced.removePrefix("character:")}-$language"
            namespaced.startsWith("series:") || namespaced.startsWith("parody:") ->
                "series/${namespaced.substringAfter(':')}-$language"
            namespaced.startsWith("group:") ->
                "group/${namespaced.removePrefix("group:")}-$language"
            else -> "tag/$namespaced-$language"
        }
    }

    private fun parseNozomi(response: Response): MangasPage {
        val bytes = response.body.bytes()
        val page = pageFromRange(response.request.header("Range"))
        val (ids, hasNext) = HitomiNozomi.slicePage(bytes, page, response.header("Content-Range"))
        val manga = fetchGalleries(ids)
        // hasNext is about the nozomi, not about how many gallery.js calls succeeded. Tying the
        // two together stopped paging the moment a few JS fetches failed.
        return MangasPage(manga, hasNext && ids.isNotEmpty())
    }

    private fun pageFromRange(range: String?): Int {
        if (range.isNullOrBlank()) return 1
        val from = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: return 1
        return from / (HitomiNozomi.PAGE_SIZE * HitomiNozomi.ID_BYTES) + 1
    }

    private fun fetchGalleries(ids: List<Long>): List<SManga> {
        if (ids.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(6)
        return try {
            ids.map { id ->
                pool.submit<SManga> {
                    loadGalleryListing(id)
                }
            }.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun loadGalleryListing(id: Long): SManga {
        repeat(2) { attempt ->
            val manga = runCatching {
                val body = client.newCall(GET("$ltnUrl/galleries/$id.js", headers)).execute()
                    .use { it.body.string() }
                galleryToManga(decodeGallery(body), listingOnly = true)
            }.getOrNull()
            if (manga != null) return manga
            if (attempt == 0) Thread.sleep(200)
        }
        // Keep the slot so a flaky JS call cannot shrink the page and look like the end of the tag.
        return SManga.create().apply {
            url = "/galleries/$id.html"
            title = id.toString()
        }
    }

    private fun decodeGallery(response: Response): HitomiGallery =
        decodeGallery(response.body.string())

    private fun decodeGallery(body: String): HitomiGallery {
        val jsonText = body.substringAfter("=", "").trim().trimEnd(';')
        return json.decodeFromString(HitomiGallery.serializer(), jsonText)
    }

    private fun loadGg(): HitomiGg.Script {
        val now = System.currentTimeMillis()
        cachedGg?.let { (fetchedAt, script) ->
            if (now - fetchedAt < GG_TTL_MS) return script
        }
        synchronized(this) {
            cachedGg?.let { (fetchedAt, script) ->
                if (now - fetchedAt < GG_TTL_MS) return script
            }
            val body = client.newCall(GET("$ltnUrl/gg.js", headers)).execute().use { it.body.string() }
            return HitomiGg.parse(body).also { cachedGg = now to it }
        }
    }

    private fun galleryToManga(gallery: HitomiGallery, listingOnly: Boolean): SManga {
        val id = gallery.numericId()
        val firstHash = gallery.files?.firstOrNull()?.hash
        return SManga.create().apply {
            url = "/galleries/$id.html"
            title = gallery.title?.ifBlank { gallery.japanese_title }.orEmpty()
            thumbnail_url = firstHash?.let(HitomiGg::thumbnailUrl)
            artist = gallery.artists?.mapNotNull { it.artist }?.joinToString()
            author = gallery.groups?.mapNotNull { it.group }?.joinToString() ?: artist
            genre = gallery.displayTags().joinToString(", ")
            status = SManga.COMPLETED
            if (!listingOnly) {
                description = buildString {
                    gallery.japanese_title?.takeIf { it.isNotBlank() && it != gallery.title }?.let {
                        appendLine("Tên khác: $it")
                    }
                    gallery.language?.takeIf { it.isNotBlank() }?.let { appendLine("Ngôn ngữ: $it") }
                    gallery.type?.takeIf { it.isNotBlank() }?.let { appendLine("Loại: $it") }
                    gallery.date?.takeIf { it.isNotBlank() }?.let { appendLine("Ngày: $it") }
                    val series = gallery.parodys?.mapNotNull { it.parody }.orEmpty()
                    if (series.isNotEmpty()) appendLine("Series: ${series.joinToString()}")
                    val characters = gallery.characters?.mapNotNull { it.character }.orEmpty()
                    if (characters.isNotEmpty()) appendLine("Nhân vật: ${characters.joinToString()}")
                }.trim()
                initialized = true
            }
        }
    }

    private fun galleryId(url: String): Long {
        val digits = GALLERY_ID_REGEX.find(url)?.groupValues?.get(1)
            ?: error("Not a Hitomi gallery url: $url")
        return digits.toLong()
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.take(19).replace('T', ' ')
        return runCatching {
            DATE_FORMAT.get()!!.parse(cleaned)?.time
        }.getOrNull() ?: 0L
    }

    private class LanguageFilter(current: String) : Filter.Select<String>(
        "Language",
        LANGUAGES.keys.toTypedArray(),
        LANGUAGES.values.indexOf(current).coerceAtLeast(0),
    ) {
        fun slug(): String = LANGUAGES[values[state]] ?: "all"
    }

    private class TypeFilter : Filter.Select<String>("Type", TYPES.keys.toTypedArray()) {
        fun slug(): String? = TYPES[values[state]]
    }

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf("All") + GENRES.keys.toTypedArray(),
    ) {
        fun slug(): String? = values.getOrNull(state)?.takeIf { state > 0 }?.let { GENRES[it] }
    }

    private class TagFilter : Filter.Text("Tag")

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val GG_TTL_MS = 30L * 60L * 1000L
        private val GALLERY_ID_REGEX = Regex("""(\d+)(?:\.html)?$""")
        private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private val LANGUAGES = linkedMapOf(
            "All" to "all",
            "English" to "english",
            "日本語" to "japanese",
            "한국어" to "korean",
            "中文" to "chinese",
            "Tiếng Việt" to "vietnamese",
            "Bahasa Indonesia" to "indonesian",
            "Русский" to "russian",
            "Español" to "spanish",
        )

        private val TYPES = linkedMapOf(
            "All" to null,
            "Doujinshi" to "doujinshi",
            "Manga" to "manga",
            "Artist CG" to "artistcg",
            "Game CG" to "gamecg",
            "Image Set" to "imageset",
            "Anime" to "anime",
        )

        private val GENRES = linkedMapOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Romance" to "romance",
            "School" to "schoolgirl",
            "Horror" to "horror",
            "Sci-Fi" to "science fiction",
            "Fantasy" to "fantasy",
            "Mystery" to "mystery",
            "Sports" to "sports",
            "Slice of Life" to "slice of life",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Harem" to "harem",
            "Yuri" to "yuri",
            "Yaoi" to "yaoi",
            "Loli" to "loli",
            "Full Color" to "full color",
        )
    }
}
