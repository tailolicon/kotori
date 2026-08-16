package eu.kanade.presentation.library.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import eu.kanade.tachiyomi.source.NovelSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.library.service.LibraryPreferences
import mihon.domain.upcoming.anime.interactor.GetUpcomingAnime
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val RESUME_PANEL_SIZE = 4

/**
 * The four most recent in-progress entries for the persistent `TIẾP TỤC` panel (T1).
 *
 * Kept to the mode being browsed. The mock draws a mixed panel, but everywhere else in this
 * app the modes are separate surfaces — Updates and History already split them — and a panel
 * that answers "carry on reading" with an anime episode is answering a different question.
 */
@Composable
fun rememberKotoriResumeItems(
    mode: MediaType,
    onOpenManga: (Long) -> Unit,
    onOpenAnime: (Long) -> Unit,
): List<KotoriTabletResumeItem> {
    // Novels ride the manga stack, so manga history hands both kinds back together and the source
    // is what tells them apart. Filter before truncating: cutting to a fixed head first meant a
    // reader with a few dozen recent manga chapters had every novel trimmed away before the
    // question was even asked, and the Novel panel came up permanently empty.
    //
    // Paired with the source list because extensions load after the first frame — the same shape
    // the library grid and History use, so the three can never disagree about what is a novel.
    val mangaHistory by produceState<List<HistoryWithRelations>>(initialValue = emptyList(), mode) {
        combine(
            Injekt.get<GetHistory>().subscribe(""),
            Injekt.get<SourceManager>().sources,
        ) { history, sources ->
            val novelSources = sources.filterIsInstance<NovelSource>().mapTo(mutableSetOf()) { it.id }
            history
                .filter { (it.coverData.sourceId in novelSources) == (mode == MediaType.NOVEL) }
                .take(RESUME_PANEL_SIZE)
        }.collectLatest { value = it }
    }
    val animeHistory by produceState<List<AnimeHistoryWithRelations>>(initialValue = emptyList()) {
        Injekt.get<GetAnimeHistory>().subscribe("").collectLatest { value = it.take(RESUME_PANEL_SIZE * 2) }
    }

    return remember(mangaHistory, animeHistory, mode) {
        val manga = mangaHistory.map { history ->
            KotoriTabletResumeItem(
                key = "manga-${history.id}",
                title = history.title,
                subtitle = "Ch. ${formatChapterNumber(history.chapterNumber)}",
                coverData = history.coverData,
                progress = 0f,
                // The row takes its accent and icon from this, and the list is already filtered to
                // one mode. Hardcoding MANGA put pink book icons under a teal Novel header.
                mode = mode,
                onClick = { onOpenManga(history.mangaId) },
            ) to (history.readAt?.time ?: 0L)
        }
        val anime = animeHistory.map { history ->
            val progress = if (history.totalSeconds > 0L) {
                history.lastSecondSeen.toFloat() / history.totalSeconds
            } else {
                0f
            }
            val remaining = (history.totalSeconds - history.lastSecondSeen).coerceAtLeast(0L)
            KotoriTabletResumeItem(
                key = "anime-${history.id}",
                title = history.title,
                subtitle = buildString {
                    append("Tập ")
                    append(formatChapterNumber(history.episodeNumber))
                    if (remaining > 0L) {
                        append(" · còn ")
                        append(formatRemaining(remaining))
                    }
                },
                coverData = history.coverData,
                progress = progress,
                mode = MediaType.ANIME,
                onClick = { onOpenAnime(history.animeId) },
            ) to (history.seenAt?.time ?: 0L)
        }
        val forMode = if (mode == MediaType.ANIME) anime else manga
        forMode
            .sortedByDescending { it.second }
            .take(RESUME_PANEL_SIZE)
            .map { it.first }
    }
}

/**
 * Anime from the library airing today, for the `HÔM NAY LÊN SÓNG` panel (T1) —
 * the same source the season calendar (T7) reads.
 */
@Composable
fun rememberKotoriAiringToday(onOpenAnime: (Long) -> Unit): List<KotoriTabletAiringItem> {
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val notifyIds by libraryPreferences.upcomingNotifyAnimeIds.collectAsState()
    val upcoming by produceState<List<Anime>>(initialValue = emptyList()) {
        Injekt.get<GetUpcomingAnime>().subscribe().collectLatest { value = it }
    }

    return remember(upcoming, notifyIds) {
        val today = LocalDate.now()
        upcoming
            .filter { it.expectedNextUpdate?.toLocalDate() == today }
            .sortedBy { it.expectedNextUpdate }
            .map { anime ->
                val time = anime.expectedNextUpdate
                    ?.atZone(ZoneId.systemDefault())
                    ?.toLocalTime()
                    ?: LocalTime.MIDNIGHT
                val id = anime.id.toString()
                KotoriTabletAiringItem(
                    key = "airing-$id",
                    time = "%02d:%02d".format(time.hour, time.minute),
                    title = anime.title,
                    notify = id in notifyIds,
                    onToggleNotify = {
                        val current = libraryPreferences.upcomingNotifyAnimeIds.get()
                        libraryPreferences.upcomingNotifyAnimeIds.set(
                            if (id in current) current - id else current + id,
                        )
                    },
                    onClick = { onOpenAnime(anime.id) },
                )
            }
    }
}

/**
 * `mm:ss` left in an episode, or `h:mm:ss` once it runs past an hour.
 *
 * Takes milliseconds because that is what the columns hold: `total_seconds` and
 * `last_second_seen` are written as `duration * 1000`, so reading them as seconds turned a
 * twenty-minute episode into "còn 492766:40".
 */
private fun formatRemaining(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
