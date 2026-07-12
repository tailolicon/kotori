package mihon.feature.stremio

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Minimal Stremio addon-protocol client (https://github.com/Stremio/stremio-addon-sdk):
 * `manifest.json` → `/catalog/{type}/{id}.json` → `/meta/{type}/{id}.json`
 * → `/stream/{type}/{videoId}.json`.
 *
 * Only http(s) streams are surfaced (debrid-resolved links, direct hosts…);
 * torrent-only results (infoHash without url) are filtered out because Kotori
 * does not bundle a torrent engine.
 */
object StremioClient {

    private val network: NetworkHelper by lazy { Injekt.get() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class Manifest(
        val id: String = "",
        val name: String = "Addon",
        val types: List<String> = emptyList(),
        val catalogs: List<Catalog> = emptyList(),
        val resources: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    )

    @Serializable
    data class Catalog(
        val type: String,
        val id: String,
        val name: String? = null,
    )

    @Serializable
    data class Meta(
        val id: String,
        val type: String? = null,
        val name: String = "",
        val poster: String? = null,
        val description: String? = null,
        val videos: List<MetaVideo> = emptyList(),
    )

    @Serializable
    data class MetaVideo(
        val id: String,
        val title: String? = null,
        val name: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    @Serializable
    data class Stream(
        val url: String? = null,
        val infoHash: String? = null,
        val name: String? = null,
        val title: String? = null,
        val description: String? = null,
    )

    @Serializable
    private data class CatalogResponse(val metas: List<Meta> = emptyList())

    @Serializable
    private data class MetaResponse(val meta: Meta? = null)

    @Serializable
    private data class StreamResponse(val streams: List<Stream> = emptyList())

    fun baseUrl(manifestUrl: String): String = manifestUrl.substringBeforeLast("/manifest.json")

    suspend fun fetchManifest(manifestUrl: String): Manifest = withContext(Dispatchers.IO) {
        val body = network.client.newCall(GET(manifestUrl)).awaitSuccess().body.string()
        json.decodeFromString(Manifest.serializer(), body)
    }

    suspend fun fetchCatalog(manifestUrl: String, catalog: Catalog): List<Meta> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(manifestUrl)}/catalog/${catalog.type}/${catalog.id}.json"
            val body = network.client.newCall(GET(url)).awaitSuccess().body.string()
            json.decodeFromString(CatalogResponse.serializer(), body).metas
        }

    suspend fun fetchMeta(manifestUrl: String, type: String, id: String): Meta? =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(manifestUrl)}/meta/$type/${id.encodeSegment()}.json"
            val body = network.client.newCall(GET(url)).awaitSuccess().body.string()
            json.decodeFromString(MetaResponse.serializer(), body).meta
        }

    /** Streams with a playable http(s) url, best-first as reported by the addon. */
    suspend fun fetchStreams(manifestUrl: String, type: String, videoId: String): List<Stream> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(manifestUrl)}/stream/$type/${videoId.encodeSegment()}.json"
            val body = network.client.newCall(GET(url)).awaitSuccess().body.string()
            json.decodeFromString(StreamResponse.serializer(), body).streams
                .filter { it.url?.startsWith("http") == true }
        }

    private fun String.encodeSegment(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
