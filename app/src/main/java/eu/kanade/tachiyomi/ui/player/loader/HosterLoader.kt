package eu.kanade.tachiyomi.ui.player.loader

import android.content.Context

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class HosterLoader {
    companion object {
        /**
         * Check for the best video from the current hosterState.
         *
         * Highest quality wins: whichever viable video has the tallest resolution the current
         * connection can carry, since a source's own ordering says nothing about quality. A
         * source that explicitly marks a video `preferred` still breaks ties. Falling back, the
         * first video with a non-empty url is selected; with no viable videos at all, an error
         * is thrown.
         *
         * @return the indices of the hoster & video
         */
        fun selectBestVideo(hosterState: List<HosterState>, context: Context? = null): Pair<Int, Int> {
            val availableHosters = hosterState.withIndex()
                .filter { (_, state) -> state is HosterState.Ready }

            val isViable: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                v.videoUrl.isNotEmpty() && (s == Video.State.READY || s == Video.State.QUEUE)
            }

            // Best resolution the link can carry, preferring an explicitly `preferred` video
            // between two of equal height.
            val maxHeight = VideoQualityPolicy.maxHeight(context)
            var bestHoster = -1
            var bestVideo = -1
            var bestHeight = Int.MIN_VALUE
            var bestPreferred = false
            availableHosters.forEach { (hosterIdx, state) ->
                val hoster = state as HosterState.Ready
                (hoster.videoList zip hoster.videoState).forEachIndexed { videoIdx, pair ->
                    if (!isViable(pair)) return@forEachIndexed
                    val (video, _) = pair
                    val height = VideoQualityPolicy.heightOf(video) ?: return@forEachIndexed
                    if (height > maxHeight) return@forEachIndexed
                    val better = height > bestHeight || (height == bestHeight && video.preferred && !bestPreferred)
                    if (better) {
                        bestHeight = height
                        bestPreferred = video.preferred
                        bestHoster = hosterIdx
                        bestVideo = videoIdx
                    }
                }
            }
            if (bestHoster != -1) return bestHoster to bestVideo

            // No video announced its resolution — fall back to the source's own preference.
            val isPreferred: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                v.preferred && (s == Video.State.READY || s == Video.State.QUEUE)
            }
            val prefHosterIdx = availableHosters.indexOfFirst {
                (it.value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }.any(isPreferred)
            }
            if (prefHosterIdx != -1) {
                val videoList = (availableHosters[prefHosterIdx].value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }
                val prefVideoIdx = videoList.indexOfFirst(isPreferred)
                return availableHosters[prefHosterIdx].index to prefVideoIdx
            }

            // Check for first video with non-empty url
            val firstValid: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                v.videoUrl.isNotEmpty() && (s == Video.State.READY || s == Video.State.QUEUE)
            }
            val firstAvailableHosterIdx = availableHosters.indexOfFirst {
                (it.value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }.any(firstValid)
            }
            if (firstAvailableHosterIdx != -1) {
                val videoList = (availableHosters[firstAvailableHosterIdx].value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }
                val firstVideoIdx = videoList.indexOfFirst(firstValid)
                return availableHosters[firstAvailableHosterIdx].index to firstVideoIdx
            }

            // No success
            return Pair(-1, -1)
        }

        class EarlyReturnException(val video: Video) : Exception()

        /**
         * Return the first loaded and valid "best" video, based on the criteria in the function `selectBestVideo` above.
         *
         * @param source The source for the episode
         * @param hosterList the list of hosters
         * @return the video, or null if no valid video was found
         */
        suspend fun getBestVideo(source: AnimeSource, hosterList: List<Hoster>): Video? {
            val hosterStates = MutableList<HosterState>(hosterList.size) { HosterState.Idle("") }

            return try {
                withContext(Dispatchers.IO) {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
                            hosterStates[hosterIdx] = hosterState

                            if (hosterState is HosterState.Ready) {
                                // Resolving a source-preferred video straight away is a latency
                                // win, but only when nothing better is on offer — otherwise it
                                // would hand back 480p on a link that can carry 1080p.
                                val maxHeight = VideoQualityPolicy.maxHeight()
                                val bestHeight = hosterState.videoList
                                    .mapNotNull { VideoQualityPolicy.heightOf(it) }
                                    .filter { it <= maxHeight }
                                    .maxOrNull()
                                val prefIndex = hosterState.videoList.indexOfFirst {
                                    it.preferred && !it.initialized &&
                                        (bestHeight == null || VideoQualityPolicy.heightOf(it) == bestHeight)
                                }
                                if (prefIndex != -1) {
                                    val video = hosterState.videoList[prefIndex]
                                    hosterStates[hosterIdx] =
                                        (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            video,
                                            Video.State.LOAD_VIDEO,
                                        )

                                    val resolvedVideo = getResolvedVideo(source, video)
                                    if (resolvedVideo?.videoUrl?.isNotEmpty() == true) {
                                        coroutineContext.cancelChildren()
                                        throw EarlyReturnException(resolvedVideo)
                                    }

                                    hosterStates[hosterIdx] =
                                        (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            video,
                                            Video.State.ERROR,
                                        )
                                }
                            }
                        }
                    }.awaitAll()

                    var (hosterIdx, videoIdx) = selectBestVideo(hosterStates)
                    while (hosterIdx != -1) {
                        val hosterState = hosterStates[hosterIdx] as HosterState.Ready
                        val video = hosterState.videoList[videoIdx]
                        hosterStates[hosterIdx] =
                            (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                video,
                                Video.State.LOAD_VIDEO,
                            )

                        val resolvedVideo = getResolvedVideo(source, video)
                        if (resolvedVideo?.videoUrl?.isNotEmpty() == true) {
                            coroutineContext.cancelChildren()
                            return@withContext resolvedVideo
                        }

                        hosterStates[hosterIdx] =
                            (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                video,
                                Video.State.ERROR,
                            )
                        val newResult = selectBestVideo(hosterStates)
                        hosterIdx = newResult.first
                        videoIdx = newResult.second
                    }

                    coroutineContext.cancelChildren()
                    return@withContext null
                }
            } catch (e: EarlyReturnException) {
                e.video
            }
        }

        suspend fun getResolvedVideo(source: AnimeSource?, video: Video): Video? {
            val resolvedVideo = if (source is AnimeHttpSource && !video.initialized) {
                try {
                    source.resolveVideo(video)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }

                    null
                }
            } else {
                video
            }

            return resolvedVideo?.copy(initialized = true)
        }
    }
}
