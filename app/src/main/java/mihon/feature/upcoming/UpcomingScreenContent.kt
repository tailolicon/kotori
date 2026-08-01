package mihon.feature.upcoming

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientButton
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.isKotoriTablet
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.launch
import mihon.feature.upcoming.components.UpcomingItem
import mihon.feature.upcoming.components.calendar.Calendar
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.YearMonth

@Composable
fun UpcomingScreenContent(
    state: UpcomingScreenModel.State,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickUpcoming: (manga: Manga) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val navigator = LocalNavigator.currentOrThrow
    val uriHandler = LocalUriHandler.current

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
                UpcomingScreenLargeImpl(
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
                UpcomingScreenSmallImpl(
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

@Composable
private fun DateHeading(
    date: LocalDate,
    mangaCount: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
    ) {
        KotoriSectionLabel(text = relativeDateText(date))
        Text(
            text = " · $mangaCount",
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            color = KotoriTheme.accent.light.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun UpcomingScreenSmallImpl(
    listState: LazyListState,
    items: List<UpcomingUIModel>,
    events: Map<LocalDate, Int>,
    paddingValues: PaddingValues,
    selectedYearMonth: YearMonth,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickDay: (LocalDate) -> Unit,
    onClickUpcoming: (manga: Manga) -> Unit,
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
                    is UpcomingUIModel.Header -> "header"
                    is UpcomingUIModel.Item -> "item"
                }
            },
        ) { item ->
            when (item) {
                is UpcomingUIModel.Item -> {
                    UpcomingItem(
                        upcoming = item.manga,
                        onClick = { onClickUpcoming(item.manga) },
                    )
                }
                is UpcomingUIModel.Header -> {
                    DateHeading(
                        date = item.date,
                        mangaCount = item.mangaCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingScreenLargeImpl(
    listState: LazyListState,
    items: List<UpcomingUIModel>,
    events: Map<LocalDate, Int>,
    paddingValues: PaddingValues,
    selectedYearMonth: YearMonth,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickDay: (LocalDate) -> Unit,
    onClickUpcoming: (manga: Manga) -> Unit,
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
                            is UpcomingUIModel.Header -> "header"
                            is UpcomingUIModel.Item -> "item"
                        }
                    },
                ) { item ->
                    when (item) {
                        is UpcomingUIModel.Item -> {
                            UpcomingItem(
                                upcoming = item.manga,
                                onClick = { onClickUpcoming(item.manga) },
                            )
                        }
                        is UpcomingUIModel.Header -> {
                            DateHeading(
                                date = item.date,
                                mangaCount = item.mangaCount,
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * T7 · Lịch mùa on tablet for the manga/novel modes — same week board as the anime
 * calendar, with the bells persisted per manga id.
 */
@Composable
private fun KotoriTabletUpcomingWeek(
    items: List<UpcomingUIModel>,
    onClickUpcoming: (Manga) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val notifyIds by libraryPreferences.upcomingNotifyMangaIds.collectAsState()
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }

    val weekStart = remember(weekOffset) {
        LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(weekOffset.toLong())
    }
    val weekEnd = remember(weekStart) { weekStart.plusDays(6) }
    val manga = remember(items) { items.filterIsInstance<UpcomingUIModel.Item>().map { it.manga } }
    val dayLabels = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    val nextNumbers by produceState(initialValue = emptyMap<Long, Double>(), manga) {
        val getChapters = Injekt.get<GetChaptersByMangaId>()
        value = withIOContext {
            manga.associate { entry ->
                entry.id to (getChapters.await(entry.id).maxOfOrNull { it.chapterNumber } ?: 0.0) + 1.0
            }
        }
    }

    val weekMangaIds = remember(manga, weekStart) {
        manga.mapNotNull { entry ->
            val date = entry.expectedNextUpdate?.toLocalDate() ?: return@mapNotNull null
            entry.id.toString().takeIf { !date.isBefore(weekStart) && !date.isAfter(weekEnd) }
        }
    }

    val days = remember(manga, weekStart, notifyIds, nextNumbers) {
        (0..6).map { index ->
            val date = weekStart.plusDays(index.toLong())
            KotoriUpcomingDay(
                date = date,
                label = dayLabels[index],
                releases = manga
                    .filter { it.expectedNextUpdate?.toLocalDate() == date }
                    .sortedBy { it.expectedNextUpdate }
                    .map { entry ->
                        val time = entry.expectedNextUpdate
                            ?.atZone(ZoneId.systemDefault())
                            ?.toLocalTime()
                        val id = entry.id.toString()
                        KotoriUpcomingRelease(
                            key = "upcoming-manga-$id-$date",
                            time = time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--",
                            title = entry.title,
                            itemLabel = nextNumbers[entry.id]
                                ?.let { "Ch. ${formatChapterNumber(it)}" }
                                ?: "Chương kế",
                            coverData = MangaCover(
                                mangaId = entry.id,
                                sourceId = entry.source,
                                isMangaFavorite = entry.favorite,
                                url = entry.thumbnailUrl,
                                lastModified = entry.coverLastModified,
                            ),
                            notify = id in notifyIds,
                            onClick = { onClickUpcoming(entry) },
                            onToggleNotify = {
                                val current = libraryPreferences.upcomingNotifyMangaIds.get()
                                libraryPreferences.upcomingNotifyMangaIds.set(
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
        title = "Lịch mùa",
        subtitle = "Tuần ${weekStart.dayOfMonth}–${weekEnd.dayOfMonth} tháng ${weekEnd.monthValue}",
        days = days,
        libraryOnly = null,
        onToggleLibraryOnly = {},
        onNotifyAll = {
            libraryPreferences.upcomingNotifyMangaIds.set(
                libraryPreferences.upcomingNotifyMangaIds.get() + weekMangaIds,
            )
        },
        onPreviousWeek = { weekOffset-- },
        onNextWeek = { weekOffset++ },
        onNavigateUp = navigator::pop,
    )
}
