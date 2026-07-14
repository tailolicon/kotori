package eu.kanade.tachiyomi.source.anime.builtin

import eu.kanade.tachiyomi.animesource.model.Hoster
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
 * It extends [AnimeHttpSource] and stubs out the abstract members that the built-in
 * scraper sources don't use (season / hoster / RxJava-era parse hooks), so each concrete
 * source only has to implement the request + parse methods it actually needs plus a
 * suspend [getVideoList] for the (often multi-step) video resolution.
 */
abstract class BuiltInHttpSource : AnimeHttpSource() {

    // Built-in sources here target Vietnamese content by default; override if needed.
    override val lang: String = "vi"

    // These built-in sources expose episodes directly, never seasons.
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    // --- Unused abstract members (this class resolves videos via suspend getVideoList) ---

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
