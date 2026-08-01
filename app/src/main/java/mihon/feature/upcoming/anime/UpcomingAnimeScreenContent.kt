package mihon.feature.upcoming.anime

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.presentation.theme.kotori.isKotoriTablet
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.core.common.Constants
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import mihon.feature.upcoming.KotoriTabletUpcomingBoard
import mihon.feature.upcoming.KotoriUpcomingDay
import mihon.feature.upcoming.KotoriUpcomingRelease
import mihon.feature.upcoming.anime.components.UpcomingItem
import mihon.feature.upcoming.components.calendar.Calendar
import eu.kanade.tachiyomi.util.lang.toLocalDate
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

@Composable
fun UpcomingAnimeScreenContent(
    state: UpcomingAnimeScreenModel.State,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickUpcoming: (anime: Anime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val onClickDay: (LocalDate, Int) -> Unit = { date, offset ->
        state.headerIndexes[date]?.let {
            scope.launch {
                listState.animateScrollToItem(it + offset)
            }
        }
    }
    if (isKotoriTablet()) {
        KotoriTabletUpcomingWeek(
            items = state.items,
            onClickUpcoming = onClickUpcoming,
            modifier = modifier,
        )
        return
    }

    Scaffold(
        topBar = { UpcomingToolbar() },
        modifier = modifier,
    ) { paddingValues ->
        if (isTabletUi()) {
            UpcomingAnimeScreenLargeImpl(
                listState = listState,
                items = state.items,
                events = state.events,
                paddingValues = paddingValues,
                selectedYearMonth = state.selectedYearMonth,
                setSelectedYearMonth = setSelectedYearMonth,
                onClickDay = { onClickDay(it, 0) },
                onClickUpcoming = onClickUpcoming,
            )
        } else {
            UpcomingAnimeScreenSmallImpl(
                listState = listState,
                items = state.items,
                events = state.events,
                paddingValues = paddingValues,
                selectedYearMonth = state.selectedYearMonth,
                setSelectedYearMonth = setSelectedYearMonth,
                onClickDay = { onClickDay(it, 1) },
                onClickUpcoming = onClickUpcoming,
            )
        }
    }
}

/**
 * T7 · Lịch mùa on tablet: the seven-column week board, with the week the arrows
 * select and the per-release bells persisted through [LibraryPreferences].
 */
@Composable
private fun KotoriTabletUpcomingWeek(
    items: ImmutableList<UpcomingAnimeUIModel>,
    onClickUpcoming: (Anime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val notifyIds by libraryPreferences.upcomingNotifyAnimeIds.collectAsState()
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }

    val weekStart = remember(weekOffset) {
        LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(weekOffset.toLong())
    }
    val anime = remember(items) { items.filterIsInstance<UpcomingAnimeUIModel.Item>().map { it.anime } }
    val dayLabels = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    // The card sub-line names the episode that is actually next, so it needs the highest
    // episode already in the database. One query per entry, re-run only when the set changes.
    val nextNumbers by produceState(initialValue = emptyMap<Long, Double>(), anime) {
        val getEpisodes = Injekt.get<GetEpisodesByAnimeId>()
        value = withIOContext {
            anime.associate { entry ->
                entry.id to (getEpisodes.await(entry.id).maxOfOrNull { it.episodeNumber } ?: 0.0) + 1.0
            }
        }
    }

    val weekEnd = remember(weekStart) { weekStart.plusDays(6) }
    val weekAnimeIds = remember(anime, weekStart) {
        anime.mapNotNull { entry ->
            val date = entry.expectedNextUpdate?.toLocalDate() ?: return@mapNotNull null
            entry.id.toString().takeIf { !date.isBefore(weekStart) && !date.isAfter(weekEnd) }
        }
    }

    val days = remember(anime, weekStart, notifyIds, nextNumbers) {
        (0..6).map { index ->
            val date = weekStart.plusDays(index.toLong())
            KotoriUpcomingDay(
                date = date,
                label = dayLabels[index],
                releases = anime
                    .filter { it.expectedNextUpdate?.toLocalDate() == date }
                    .sortedBy { it.expectedNextUpdate }
                    .map { entry ->
                        val time = entry.expectedNextUpdate
                            ?.atZone(ZoneId.systemDefault())
                            ?.toLocalTime()
                        val id = entry.id.toString()
                        KotoriUpcomingRelease(
                            key = "upcoming-$id-$date",
                            time = time
                                ?.takeIf { it.hour != 0 || it.minute != 0 }
                                ?.let { "%02d:%02d".format(it.hour, it.minute) },
                            title = entry.title,
                            itemLabel = nextNumbers[entry.id]
                                ?.let { "Tập ${formatEpisodeNumber(it)}" }
                                ?: "Tập kế",
                            coverData = AnimeCover(
                                animeId = entry.id,
                                sourceId = entry.source,
                                isAnimeFavorite = entry.favorite,
                                url = entry.thumbnailUrl,
                                lastModified = entry.coverLastModified,
                            ),
                            notify = id in notifyIds,
                            onClick = { onClickUpcoming(entry) },
                            onToggleNotify = {
                                val current = libraryPreferences.upcomingNotifyAnimeIds.get()
                                libraryPreferences.upcomingNotifyAnimeIds.set(
                                    if (id in current) current - id else current + id,
                                )
                            },
                        )
                    },
            )
        }
    }

    KotoriTabletUpcomingBoard(
        modifier = modifier,
        title = stringResource(MR.strings.label_upcoming),
        subtitle = "Tuần ${weekStart.dayOfMonth}–${weekEnd.dayOfMonth} tháng ${weekEnd.monthValue}",
        days = days,
        libraryOnly = null,
        onToggleLibraryOnly = {},
        onNotifyAll = {
            libraryPreferences.upcomingNotifyAnimeIds.set(
                libraryPreferences.upcomingNotifyAnimeIds.get() + weekAnimeIds,
            )
        },
        onPreviousWeek = { weekOffset-- },
        onNextWeek = { weekOffset++ },
        onNavigateUp = navigator::pop,
    )
}

@Composable
private fun UpcomingToolbar() {
    val navigator = LocalNavigator.currentOrThrow
    val uriHandler = LocalUriHandler.current

    AppBar(
        title = stringResource(MR.strings.label_upcoming),
        navigateUp = navigator::pop,
        actions = {
            IconButton(onClick = { uriHandler.openUri(Constants.URL_HELP_UPCOMING) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = stringResource(MR.strings.upcoming_guide),
                )
            }
        },
    )
}

@Composable
private fun DateHeading(
    date: LocalDate,
    animeCount: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = relativeDateText(date),
            modifier = Modifier
                .padding(MaterialTheme.padding.small)
                .padding(start = MaterialTheme.padding.small),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Badge(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text("$animeCount")
        }
    }
}

@Composable
private fun UpcomingAnimeScreenSmallImpl(
    listState: LazyListState,
    items: ImmutableList<UpcomingAnimeUIModel>,
    events: ImmutableMap<LocalDate, Int>,
    paddingValues: PaddingValues,
    selectedYearMonth: YearMonth,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickDay: (LocalDate) -> Unit,
    onClickUpcoming: (anime: Anime) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = paddingValues,
        state = listState,
    ) {
        item(key = "upcoming-calendar") {
            Calendar(
                selectedYearMonth = selectedYearMonth,
                events = events,
                setSelectedYearMonth = setSelectedYearMonth,
                onClickDay = onClickDay,
            )
        }
        items(
            items = items,
            key = { "upcoming-${it.hashCode()}" },
            contentType = {
                when (it) {
                    is UpcomingAnimeUIModel.Header -> "header"
                    is UpcomingAnimeUIModel.Item -> "item"
                }
            },
        ) { item ->
            when (item) {
                is UpcomingAnimeUIModel.Item -> {
                    UpcomingItem(
                        upcoming = item.anime,
                        onClick = { onClickUpcoming(item.anime) },
                    )
                }
                is UpcomingAnimeUIModel.Header -> {
                    DateHeading(
                        date = item.date,
                        animeCount = item.animeCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingAnimeScreenLargeImpl(
    listState: LazyListState,
    items: ImmutableList<UpcomingAnimeUIModel>,
    events: ImmutableMap<LocalDate, Int>,
    paddingValues: PaddingValues,
    selectedYearMonth: YearMonth,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickDay: (LocalDate) -> Unit,
    onClickUpcoming: (anime: Anime) -> Unit,
) {
    TwoPanelBox(
        modifier = Modifier.padding(paddingValues),
        startContent = {
            Calendar(
                selectedYearMonth = selectedYearMonth,
                events = events,
                setSelectedYearMonth = setSelectedYearMonth,
                onClickDay = onClickDay,
            )
        },
        endContent = {
            FastScrollLazyColumn(state = listState) {
                items(
                    items = items,
                    key = { "upcoming-${it.hashCode()}" },
                    contentType = {
                        when (it) {
                            is UpcomingAnimeUIModel.Header -> "header"
                            is UpcomingAnimeUIModel.Item -> "item"
                        }
                    },
                ) { item ->
                    when (item) {
                        is UpcomingAnimeUIModel.Item -> {
                            UpcomingItem(
                                upcoming = item.anime,
                                onClick = { onClickUpcoming(item.anime) },
                            )
                        }
                        is UpcomingAnimeUIModel.Header -> {
                            DateHeading(
                                date = item.date,
                                animeCount = item.animeCount,
                            )
                        }
                    }
                }
            }
        },
    )
}
