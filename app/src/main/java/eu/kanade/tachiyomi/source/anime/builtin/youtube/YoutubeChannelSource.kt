package eu.kanade.tachiyomi.source.anime.builtin.youtube

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.anime.builtin.BuiltInHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Built-in source that turns a single YouTube channel into a catalogue, using NewPipeExtractor.
 *
 * Mapping requested by the user: **one playlist = one anime**, so episodes of a series stay
 * grouped instead of scattered per-video. Playlist items become episodes; playback resolves the
 * YouTube stream (HLS when available, otherwise DASH video + separate audio track) into mpv.
 *
 * Note: YouTube now gates stream URLs behind poTokens; metadata (playlists/episodes) works without
 * one, but playback may require a [org.schabi.newpipe.extractor.services.youtube.PoTokenProvider].
 */
abstract class YoutubeChannelSource(
    override val name: String,
    private val channelUrl: String,
) : BuiltInHttpSource() {

    override val baseUrl = "https://www.youtube.com"
    override val supportsLatest = true

    private val service get() = ServiceList.YouTube

    // ============================== Browse: playlists = series ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchPlaylists(null)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchPlaylists(null)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        fetchPlaylists(query)

    private suspend fun fetchPlaylists(query: String?): AnimesPage = withContext(Dispatchers.IO) {
        ensureInit(client)
        val channel = ChannelInfo.getInfo(service, channelUrl)
        val tab = channel.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.PLAYLISTS) }
            ?: return@withContext AnimesPage(emptyList(), false)

        val tabInfo = ChannelTabInfo.getInfo(service, tab)
        val items = tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>().toMutableList()

        var next = tabInfo.nextPage
        var guard = 0
        while (next != null && guard < 5) {
            val more = ChannelTabInfo.getMoreItems(service, tab, next)
            items += more.items.filterIsInstance<PlaylistInfoItem>()
            next = more.nextPage
            guard++
        }

        val filtered = if (query.isNullOrBlank()) {
            items
        } else {
            items.filter { it.name.contains(query, ignoreCase = true) }
        }
        AnimesPage(filtered.map { it.toSAnime() }, false)
    }

    private fun PlaylistInfoItem.toSAnime(): SAnime {
        val item = this
        return SAnime.create().apply {
            setUrlWithoutDomain(item.url)
            title = item.name
            thumbnail_url = item.thumbnails.bestUrl()
            author = item.uploaderName
            initialized = false
        }
    }

    // ============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        ensureInit(client)
        val playlist = PlaylistInfo.getInfo(service, baseUrl + anime.url)
        SAnime.create().apply {
            url = anime.url
            title = playlist.name
            thumbnail_url = playlist.thumbnails.bestUrl() ?: anime.thumbnail_url
            author = playlist.uploaderName
            genre = name
            status = SAnime.UNKNOWN
            description = buildString {
                playlist.uploaderName?.takeIf { it.isNotBlank() }?.let { appendLine("Kênh: $it") }
                appendLine("Số tập: ${playlist.streamCount}")
                playlist.description?.content?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    append(it)
                }
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        ensureInit(client)
        val url = baseUrl + anime.url
        val playlist = PlaylistInfo.getInfo(service, url)
        val streams = playlist.relatedItems.toMutableList()

        var next = playlist.nextPage
        var guard = 0
        while (next != null && guard < 20) {
            val more = PlaylistInfo.getMoreItems(service, url, next)
            streams += more.items
            next = more.nextPage
            guard++
        }

        streams.mapIndexed { index, item ->
            SEpisode.create().apply {
                setUrlWithoutDomain(item.url)
                name = item.name
                episode_number = (index + 1).toFloat()
            }
        }
    }

    // ============================== Video ==============================

    override suspend fun resolveVideos(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        ensureInit(client)
        val info = StreamInfo.getInfo(service, baseUrl + episode.url)

        val subtitles = info.subtitles.mapNotNull { sub ->
            sub.content?.takeIf { it.isNotBlank() }?.let {
                Track(it, sub.displayLanguageName ?: sub.languageTag ?: "sub")
            }
        }
        val bestAudioUrl = info.audioStreams
            .filter { it.content?.isNotBlank() == true }
            .maxByOrNull { it.averageBitrate }
            ?.content

        val videos = mutableListOf<Video>()

        // 1) HLS master (single URL, audio+video muxed) — nicest for mpv when present.
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let {
            videos += Video(videoUrl = it, videoTitle = "HLS (auto)", subtitleTracks = subtitles)
        }

        // 2) DASH video-only streams paired with the best audio track.
        info.videoOnlyStreams
            .filter { it.content?.isNotBlank() == true }
            .sortedByDescending { it.resolution.resHeight() }
            .forEach { vs ->
                videos += Video(
                    videoUrl = vs.content,
                    videoTitle = vs.resolution.ifBlank { "video" },
                    audioTracks = bestAudioUrl?.let { listOf(Track(it, "audio")) } ?: emptyList(),
                    subtitleTracks = subtitles,
                )
            }

        // 3) Muxed progressive streams (already contain audio) as a fallback.
        info.videoStreams
            .filter { it.content?.isNotBlank() == true }
            .sortedByDescending { it.resolution.resHeight() }
            .forEach { vs ->
                videos += Video(
                    videoUrl = vs.content,
                    videoTitle = vs.resolution.ifBlank { "video" },
                    subtitleTracks = subtitles,
                )
            }

        videos
    }

    // ============================== Helpers ==============================

    private fun List<Image>.bestUrl(): String? =
        maxByOrNull { it.height }?.url ?: firstOrNull()?.url

    private fun String.resHeight(): Int = substringBefore("p").trim().toIntOrNull() ?: 0

    // ---- Abstract request/parse hooks are unused (we override the suspend getX methods) ----
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException("Not used")
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException("Not used")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        throw UnsupportedOperationException("Not used")
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException("Not used")
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException("Not used")
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException("Not used")

    companion object {
        @Volatile
        private var initialized = false

        @Synchronized
        private fun ensureInit(client: OkHttpClient) {
            if (initialized) return
            NewPipe.init(NewPipeDownloader(client), Localization("vi", "VN"), ContentCountry("VN"))
            initialized = true
        }
    }
}

/** Muse Việt Nam — official licensed anime (Vietnamese subs). */
class MuseVietnamSource : YoutubeChannelSource(
    name = "Muse Việt Nam (YouTube)",
    channelUrl = "https://www.youtube.com/channel/UCott96qGP5ADmsB_yNQMvDA",
)

/** Ani-One Vietnam — official licensed anime (Vietnamese subs). */
class AniOneVietnamSource : YoutubeChannelSource(
    name = "Ani-One Vietnam (YouTube)",
    channelUrl = "https://www.youtube.com/@AniOneVietnam",
)
