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
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import eu.kanade.tachiyomi.source.NovelSource
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.core.common.util.lang.withIOContext
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
    val mangaHistory by produceState<List<HistoryWithRelations>>(initialValue = emptyList()) {
        Injekt.get<GetHistory>().subscribe("").collectLatest { value = it.take(RESUME_PANEL_SIZE * 8) }
    }
    val animeHistory by produceState<List<AnimeHistoryWithRelations>>(initialValue = emptyList()) {
        Injekt.get<GetAnimeHistory>().subscribe("").collectLatest { value = it.take(RESUME_PANEL_SIZE * 2) }
    }

    val isNovelEntry = rememberNovelEntryLookup(remember(mangaHistory) { mangaHistory.map { it.mangaId } })

    return remember(mangaHistory, animeHistory, mode, isNovelEntry) {
        val manga = mangaHistory.filter { isNovelEntry(it.mangaId) == (mode == MediaType.NOVEL) }.map { history ->
            KotoriTabletResumeItem(
                key = "manga-${history.id}",
                title = history.title,
                subtitle = "Ch. ${formatChapterNumber(history.chapterNumber)}",
                coverData = history.coverData,
                progress = 0f,
                mode = MediaType.MANGA,
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

private fun formatRemaining(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return "%02d:%02d".format(minutes, rest)
}

/**
 * Whether a history entry is a novel.
 *
 * Novels ride the manga stack, so history hands both back together; the source is what tells
 * them apart. Resolved through the source manager rather than the database because that is
 * where the novel flag lives.
 */
@Composable
private fun rememberNovelEntryLookup(ids: List<Long>): (Long) -> Boolean {
    val novelIds by produceState(initialValue = emptySet<Long>(), ids) {
        val getManga = Injekt.get<GetManga>()
        val sourceManager = Injekt.get<SourceManager>()
        value = withIOContext {
            ids.mapNotNull { id ->
                val manga = getManga.await(id) ?: return@mapNotNull null
                id.takeIf { sourceManager.get(manga.source) is NovelSource }
            }.toSet()
        }
    }
    return { it in novelIds }
}
