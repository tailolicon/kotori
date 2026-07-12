package mihon.feature.mediasource

import eu.kanade.domain.ui.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.localmedia.LocalMediaEntry
import mihon.feature.localmedia.LocalMediaFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online anime/novel source contract (design "content-type union" + screen 17
 * type tags). Extensions implement this to serve catalogs over the network;
 * episode/chapter URIs are `http(s)` and stream straight into the player /
 * novel reader. Two built-in public-domain sources ship as reference
 * implementations of the contract.
 */
interface OnlineMediaSource {
    val id: Long
    val name: String
    val lang: String
    val type: MediaType

    /** Catalog of titles; each entry's files carry streamable http(s) URIs. */
    suspend fun fetchCatalog(): List<LocalMediaEntry>
}

/** Reference anime source: public-domain shorts streamed over https. */
object SampleAnimeSource : OnlineMediaSource {
    override val id = -900L
    override val name = "Kotori Demo Anime"
    override val lang = "EN"
    override val type = MediaType.ANIME

    override suspend fun fetchCatalog(): List<LocalMediaEntry> = listOf(
        LocalMediaEntry(
            type = MediaType.ANIME,
            title = "Big Buck Bunny",
            files = listOf(
                LocalMediaFile(name = "Trailer", uri = "https://media.w3.org/2010/05/bunny/trailer.mp4"),
                LocalMediaFile(name = "Movie", uri = "https://media.w3.org/2010/05/bunny/movie.mp4"),
            ),
        ),
        LocalMediaEntry(
            type = MediaType.ANIME,
            title = "Sintel",
            files = listOf(
                LocalMediaFile(name = "Trailer", uri = "https://media.w3.org/2010/05/sintel/trailer.mp4"),
            ),
        ),
    )
}

/** Reference novel source: Project Gutenberg plain-text books. */
object SampleNovelSource : OnlineMediaSource {
    override val id = -901L
    override val name = "Kotori Demo Novel"
    override val lang = "EN"
    override val type = MediaType.NOVEL

    override suspend fun fetchCatalog(): List<LocalMediaEntry> = listOf(
        LocalMediaEntry(
            type = MediaType.NOVEL,
            title = "Alice in Wonderland",
            files = listOf(
                LocalMediaFile(
                    name = "Alice in Wonderland",
                    uri = "https://www.gutenberg.org/cache/epub/11/pg11.txt",
                ),
            ),
        ),
        LocalMediaEntry(
            type = MediaType.NOVEL,
            title = "Pride and Prejudice",
            files = listOf(
                LocalMediaFile(
                    name = "Pride and Prejudice",
                    uri = "https://www.gutenberg.org/cache/epub/1342/pg1342.txt",
                ),
            ),
        ),
    )
}

/** Registry of installed online media sources. Extensions register here. */
object OnlineMediaSourceRegistry {
    private val sources = mutableListOf<OnlineMediaSource>(SampleAnimeSource, SampleNovelSource)

    fun register(source: OnlineMediaSource) {
        if (sources.none { it.id == source.id }) sources += source
    }

    fun sourcesFor(type: MediaType): List<OnlineMediaSource> = sources.filter { it.type == type }
}

suspend fun fetchOnlineCatalog(type: MediaType): List<Pair<OnlineMediaSource, List<LocalMediaEntry>>> =
    withContext(Dispatchers.IO) {
        OnlineMediaSourceRegistry.sourcesFor(type).map { source ->
            source to runCatching { source.fetchCatalog() }.getOrDefault(emptyList())
        }
    }

/** Fetch a text chapter over http(s) with a sane size cap. */
suspend fun fetchOnlineText(url: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.inputStream.use { stream ->
            val bytes = stream.readBytes()
            val capped = if (bytes.size > MAX_TEXT_BYTES) bytes.copyOf(MAX_TEXT_BYTES) else bytes
            capped.decodeToString()
        }
    }.getOrElse { "Không tải được nội dung: ${it.message}" }
}

private const val MAX_TEXT_BYTES = 512 * 1024
