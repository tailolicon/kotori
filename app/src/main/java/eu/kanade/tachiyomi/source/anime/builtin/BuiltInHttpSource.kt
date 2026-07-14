package eu.kanade.tachiyomi.source.anime.builtin

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Response

/**
 * Base class for the anime sources that are compiled directly into Kotori (built-in),
 * as opposed to installed extension APKs. These are registered in
 * [eu.kanade.tachiyomi.source.anime.AndroidAnimeSourceManager] beside the local source.
 *
 * App-side [AnimeHttpSource] subclasses must implement the (abstract) hoster hooks, which makes
 * the player's `EpisodeLoader.checkHasHosters` route them through the hoster API. So instead of
 * the legacy `getVideoList(episode)` path, concrete sources implement [resolveVideos] and this
 * base wraps the result in a single hoster via [getHosterList].
 */
abstract class BuiltInHttpSource : AnimeHttpSource() {

    // Built-in sources here target Vietnamese content by default; override if needed.
    override val lang: String = "vi"

    /**
     * Icon shown in the source list (web favicon or channel avatar). Loaded by
     * `AnimeSourceIcon` since built-in sources have no extension APK to pull an icon from.
     */
    abstract val iconUrl: String

    /**
     * Resolve the playable video(s) for an [episode] (often multi-step: page -> embed -> stream).
     * The first video with a non-empty url is auto-selected by the player.
     */
    abstract suspend fun resolveVideos(episode: SEpisode): List<Video>

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
        resolveVideos(episode).toHosterList()

    // These built-in sources expose episodes directly, never seasons.
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    // --- Unused abstract members (video resolution goes through resolveVideos/getHosterList) ---

    override fun episodeVideoParse(response: Response): SEpisode =
        throw UnsupportedOperationException("Not used")

    override fun hosterListParse(response: Response): List<Hoster> =
        throw UnsupportedOperationException("Not used")

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> =
        throw UnsupportedOperationException("Not used")

    override fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException("Not used")

    override fun videoUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
