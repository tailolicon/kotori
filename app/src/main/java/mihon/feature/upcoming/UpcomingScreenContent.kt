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
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientButton
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.util.isTabletUi
import kotlinx.coroutines.launch
import mihon.feature.upcoming.components.UpcomingItem
import mihon.feature.upcoming.components.calendar.Calendar
import tachiyomi.core.common.Constants
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate
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
