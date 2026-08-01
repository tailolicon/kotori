package mihon.domain.airing.interactor

import eu.kanade.tachiyomi.data.track.TrackerManager
import mihon.data.airing.AniListAiringApi
import mihon.domain.airing.model.AiringRelease
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The airing schedule for a week, with the user's own library folded in.
 *
 * The schedule itself is everything AniList says is broadcasting; a series the user tracks or
 * has in their library is marked so the calendar can point at the entry and so `Chỉ thư viện`
 * has something real to filter on.
 */
class GetAiringSchedule(
    private val api: AniListAiringApi,
    private val getAnimeTracks: GetAnimeTracks,
    private val getAnimeFavorites: GetAnimeFavorites,
) {

    /** Weeks already fetched this session — the schedule does not move minute to minute. */
    private val cache = mutableMapOf<LocalDate, List<AiringRelease>>()

    suspend fun await(weekStart: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AiringRelease> {
        val schedule = cache.getOrPut(weekStart) {
            val from = weekStart.atStartOfDay(zone).toInstant()
            val to = weekStart.plusDays(7).atStartOfDay(zone).toInstant()
            api.schedule(from, to)
        }
        return schedule.markLibraryEntries()
    }

    /** Drops the cached week so a pull-to-refresh actually refetches. */
    fun invalidate() = cache.clear()

    /**
     * Matches a broadcast to a library entry by its AniList tracking link first — that is an
     * exact id — and falls back to a normalised title, which is how an untracked but present
     * series still lights up.
     */
    private suspend fun List<AiringRelease>.markLibraryEntries(): List<AiringRelease> {
        val favorites = getAnimeFavorites.await()
        if (favorites.isEmpty()) return this

        val byRemoteId = HashMap<Long, Long>()
        favorites.forEach { anime ->
            getAnimeTracks.await(anime.id)
                .filter { it.trackerId == TrackerManager.ANILIST }
                .forEach { track -> byRemoteId[track.remoteId] = anime.id }
        }
        val byTitle = favorites.associateBy { it.title.normalisedTitle() }

        return map { release ->
            val animeId = byRemoteId[release.remoteId] ?: byTitle[release.title.normalisedTitle()]?.id
            if (animeId == null) release else release.copy(inLibrary = true, animeId = animeId)
        }
    }
}

/**
 * Enough normalisation to survive the gap between a source's title and AniList's: case,
 * punctuation and the season/subtitle tails that sources tack on.
 */
private fun String.normalisedTitle(): String = lowercase()
    .replace(TITLE_NOISE, " ")
    .trim()
    .replace(WHITESPACE, " ")

private val TITLE_NOISE = Regex("""[\p{Punct}　-〿＀-￯]+""")
private val WHITESPACE = Regex("""\s+""")
