package mihon.feature.animeplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Drives the portrait Kotori anime player (design screen 03). Loads the anime and its episode list,
 * resolves the playable video for the active episode via [EpisodeLoader], feeds it to the shared
 * [ExoPlayer], restores the resume position, and persists watch progress. Episode switching from the
 * list below the video re-resolves and reloads without leaving the screen.
 */
class AnimePlayerViewModel(
    val player: ExoPlayer,
    private val animeId: Long,
    initialEpisodeId: Long,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val upsertHistory: UpsertAnimeHistory = Injekt.get(),
    private val playerPreferences: PlayerPreferences = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    var anime by mutableStateOf<Anime?>(null)
        private set

    /** Episodes in ascending episode-number order, matching the list shown under the player. */
    var episodes by mutableStateOf<List<Episode>>(emptyList())
        private set

    var currentEpisode by mutableStateOf<Episode?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var currentEpisodeId = initialEpisodeId

    fun start() {
        scope.launch {
            val loadedAnime = getAnime.await(animeId)
            anime = loadedAnime
            episodes = getEpisodesByAnimeId.await(animeId).sortedBy { it.episodeNumber }
            loadEpisode(currentEpisodeId)
        }
    }

    fun loadEpisode(episodeId: Long) {
        val loadedAnime = anime ?: return
        val episode = episodes.find { it.id == episodeId } ?: return

        // Persist the position of the episode we are leaving before switching.
        currentEpisode?.let { saveProgress(it, player.currentPosition, player.duration.coerceAtLeast(0)) }

        currentEpisodeId = episodeId
        currentEpisode = episode
        loading = true
        errorMessage = null

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val source: AnimeSource = sourceManager.getOrStub(loadedAnime.source)
                val video = withIOContext {
                    val hosters = EpisodeLoader.getHosters(episode, loadedAnime, source)
                    val hoster = hosters.firstOrNull() ?: error("No hoster found")
                    val state = EpisodeLoader.loadHosterVideos(source, hoster, force = true)
                    (state as? HosterState.Ready)?.videoList?.firstOrNull() ?: error("No playable video found")
                }

                withUIContext {
                    val headers = video.headers?.let { h ->
                        (0 until h.size).associate { h.name(it) to h.value(it) }
                    }.orEmpty()
                    val httpFactory = DefaultHttpDataSource.Factory().apply {
                        if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
                    }
                    val mediaSource = DefaultMediaSourceFactory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(video.videoUrl))

                    player.setMediaSource(mediaSource)
                    player.prepare()
                    if (!episode.seen && episode.lastSecondSeen > 0L) {
                        player.seekTo(episode.lastSecondSeen)
                    }
                    player.playWhenReady = true
                    loading = false
                }

                upsertHistory.await(AnimeHistoryUpdate(episodeId, Date()))
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to load anime episode $episodeId" }
                withUIContext {
                    errorMessage = e.message ?: "Không phát được tập này"
                    loading = false
                }
            }
        }
    }

    fun saveActiveProgress() {
        val episode = currentEpisode ?: return
        saveProgress(episode, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    private fun saveProgress(episode: Episode, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L || positionMs <= 0L) return
        val progress = playerPreferences.progressPreference().get()
        val nearEnd = durationMs - positionMs <= MARK_SEEN_TAIL_MS
        val seen = episode.seen || positionMs >= durationMs * progress || nearEnd
        scope.launch {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episode.id,
                    seen = seen,
                    lastSecondSeen = positionMs,
                    totalSeconds = durationMs,
                ),
            )
        }
    }

    fun onDestroy() {
        saveActiveProgress()
        loadJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}

private const val MARK_SEEN_TAIL_MS = 2 * 60 * 1000L
