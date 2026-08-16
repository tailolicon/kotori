package eu.kanade.tachiyomi.source.anime.builtin

import eu.kanade.tachiyomi.animesource.BuiltInAnimeSource
import eu.kanade.tachiyomi.animesource.online.HosterAnimeSource

/**
 * Base class for the anime sources that are compiled directly into Kotori (built-in),
 * as opposed to installed extension APKs. These are registered in
 * [eu.kanade.tachiyomi.source.anime.AndroidAnimeSourceManager] beside the local source.
 *
 * Everything about resolving an episode lives in [HosterAnimeSource], which is in `source-api` so
 * extension APKs can extend it too. What is left here is what only a built-in needs: the marker the
 * data layer recognises, and an icon URL, because a built-in has no extension package to take one
 * from.
 */
abstract class BuiltInHttpSource : HosterAnimeSource(), BuiltInAnimeSource {

    /**
     * Icon shown in the source list (web favicon or channel avatar). Loaded by
     * `AnimeSourceIcon` since built-in sources have no extension APK to pull an icon from.
     */
    abstract val iconUrl: String
}
