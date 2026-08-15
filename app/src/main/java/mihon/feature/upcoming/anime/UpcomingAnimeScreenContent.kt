package mihon.feature.upcoming.anime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientButton
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.isKotoriTablet
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.core.common.Constants
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import mihon.domain.airing.interactor.GetAiringSchedule
import mihon.domain.airing.model.AiringRelease
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
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
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

    val navigator = LocalNavigator.currentOrThrow
    val uriHandler = LocalUriHandler.current
    val isTablet = isTabletUi()
    KotoriScreenScaffold(
        modifier = modifier,
        header = {
            KotoriHeader(
                title = "Lịch mùa",
                subtitle = "Tháng ${state.selectedYearMonth.monthValue} · ${state.selectedYearMonth.year}",
                onNavigateUp = navigator::pop,
                actions = {
                    KotoriHeaderAction(
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = stringResource(MR.strings.upcoming_guide),
                        onClick = { uriHandler.openUri(Constants.URL_HELP_UPCOMING) },
                    )
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (isTablet) {
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

            // `Hôm nay`: jump back to today's releases
            if (state.headerIndexes.containsKey(LocalDate.now())) {
                GradientButton(
                    onClick = {
                        setSelectedYearMonth(YearMonth.now())
                        onClickDay(LocalDate.now(), if (isTablet) 0 else 1)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                    contentPadding = 10.dp,
                ) {
                    Text(
                        text = "Hôm nay",
                        color = KotoriTheme.accent.onAccent,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
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
    val uriHandler = LocalUriHandler.current
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val notifyIds by libraryPreferences.upcomingNotifyAnimeIds.collectAsState()
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var libraryOnly by rememberSaveable { mutableStateOf(false) }

    val weekStart = remember(weekOffset) {
        LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(weekOffset.toLong())
    }
    val weekEnd = remember(weekStart) { weekStart.plusDays(6) }
    val dayLabels = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    // The broadcast schedule itself, not the library's guess at when the next episode lands:
    // an entry the user has never added still shows, and one they have is marked.
    val schedule by produceState<List<AiringRelease>?>(initialValue = null, weekStart) {
        value = Injekt.get<GetAiringSchedule>().await(weekStart)
    }

    val days = remember(schedule, weekStart, notifyIds, libraryOnly) {
        val releases = schedule.orEmpty().filter { !libraryOnly || it.inLibrary }
        (0..6).map { index ->
            val date = weekStart.plusDays(index.toLong())
            KotoriUpcomingDay(
                date = date,
                label = dayLabels[index],
                releases = releases
                    .filter { it.airingAt.atZone(ZoneId.systemDefault()).toLocalDate() == date }
                    .map { release ->
                        val time = release.airingAt.atZone(ZoneId.systemDefault()).toLocalTime()
                        val id = release.remoteId.toString()
                        KotoriUpcomingRelease(
                            key = "airing-$id-${release.episode}",
                            time = "%02d:%02d".format(time.hour, time.minute),
                            title = release.title,
                            itemLabel = "Tập ${release.episode}",
                            coverData = release.coverUrl,
                            notify = id in notifyIds,
                            inLibrary = release.inLibrary,
                            onClick = {
                                val animeId = release.animeId
                                if (animeId != null) {
                                    onClickUpcoming(Anime.create().copy(id = animeId))
                                } else {
                                    // Not in the library yet — the useful thing a tap can do is
                                    // go looking for it rather than nothing at all.
                                    navigator.push(GlobalAnimeSearchScreen(release.title))
                                }
                            },
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
        loading = schedule == null,
        libraryOnly = libraryOnly,
        onToggleLibraryOnly = { libraryOnly = !libraryOnly },
        onNotifyAll = {
            val ids = days.flatMap { it.releases }.map { it.key.substringAfter("airing-").substringBefore("-") }
            libraryPreferences.upcomingNotifyAnimeIds.set(
                libraryPreferences.upcomingNotifyAnimeIds.get() + ids,
            )
        },
        onPreviousWeek = { weekOffset-- },
        onNextWeek = { weekOffset++ },
        onToday = { weekOffset = 0 },
        onHelp = { uriHandler.openUri(Constants.URL_HELP_UPCOMING) },
        onNavigateUp = navigator::pop,
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
