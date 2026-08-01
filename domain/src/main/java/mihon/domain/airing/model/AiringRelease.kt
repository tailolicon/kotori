package mihon.domain.airing.model

import java.time.Instant

/**
 * One episode airing at a known time, from the season schedule rather than from a guess.
 *
 * The library's own `next_update` is an estimate extrapolated from how often past episodes
 * arrived, so it is empty for anything newly added and silent about series the user has not
 * added at all. A real broadcast schedule is what the season calendar is supposed to show.
 */
data class AiringRelease(
    /** AniList media id — the join key back to a tracked library entry. */
    val remoteId: Long,
    val title: String,
    val coverUrl: String?,
    val episode: Int,
    val airingAt: Instant,
    /** True when the user has this series in their anime library. */
    val inLibrary: Boolean = false,
    /** Set once matched, so tapping the card can open the entry. */
    val animeId: Long? = null,
)
