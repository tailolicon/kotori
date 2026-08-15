package eu.kanade.presentation.entries.anime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import aniyomi.domain.anime.SeasonAnime
import aniyomi.domain.anime.SeasonDisplayMode
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.entries.anime.model.episodesFiltered
import eu.kanade.domain.entries.anime.model.seasonsFiltered
import eu.kanade.presentation.components.relativeDateTimeText
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.presentation.entries.EntryScreenItem
import eu.kanade.presentation.entries.anime.components.AnimeActionRow
import eu.kanade.presentation.entries.anime.components.AnimeWatchButton
import eu.kanade.presentation.entries.anime.components.AnimeEpisodeListItem
import eu.kanade.presentation.entries.anime.components.AnimeInfoBox
import eu.kanade.presentation.entries.anime.components.AnimeSeasonListItem
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.entries.anime.components.ExpandableAnimeDescription
import eu.kanade.presentation.entries.anime.components.NextEpisodeAiringListItem
import eu.kanade.presentation.entries.components.EntryBottomActionMenu
import eu.kanade.presentation.entries.components.EntryToolbar
import eu.kanade.presentation.components.EntryDownloadDropdownMenu
import eu.kanade.presentation.entries.components.KotoriDetailChip
import eu.kanade.presentation.entries.components.KotoriDetailMenuAction
import eu.kanade.presentation.entries.components.KotoriTabletDetailItem
import eu.kanade.presentation.entries.components.KotoriTabletDetailLayout
import eu.kanade.presentation.entries.components.ItemHeader
import eu.kanade.presentation.entries.components.MissingItemCountListItem
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.AuroraBackground
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.source.anime.getNameForAnimeInfo
import eu.kanade.tachiyomi.ui.browse.anime.extension.details.AnimeSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreenModel
import eu.kanade.tachiyomi.ui.entries.anime.AnimeSeasonItem
import eu.kanade.tachiyomi.ui.entries.anime.EpisodeList
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.delay
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.service.missingEntriesCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.ExtendedFloatingActionButton
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB
import tachiyomi.source.local.entries.anime.isLocal
import java.time.Instant
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.Arrangement

@Composable
fun AnimeScreen(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    navigateUp: () -> Unit,
    onEpisodeClicked: (episode: Episode, alt: Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    changeAnimeSkipIntro: (() -> Unit)?,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,

    // For episode swipe
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,

    // Episode selection
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onAllEpisodeSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,

    // Season clicked
    onSeasonClicked: (SeasonAnime) -> Unit,
    onContinueWatchingClicked: ((SeasonAnime) -> Unit)?,
) {
    val context = LocalContext.current
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    val navigator = LocalNavigator.currentOrThrow
    val onSettingsClicked: (() -> Unit)? = {
        navigator.push(AnimeSourcePreferencesScreen(state.source.id))
    }.takeIf { state.source is ConfigurableAnimeSource }

    if (!isTabletUi) {
        AnimeScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            episodeSwipeStartAction = episodeSwipeStartAction,
            episodeSwipeEndAction = episodeSwipeEndAction,
            showNextEpisodeAirTime = showNextEpisodeAirTime,
            alwaysUseExternalPlayer = alwaysUseExternalPlayer,
            navigateUp = navigateUp,
            onEpisodeClicked = onEpisodeClicked,
            onDownloadEpisode = onDownloadEpisode,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueWatching = onContinueWatching,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            changeAnimeSkipIntro = changeAnimeSkipIntro,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiFillermarkClicked = onMultiFillermarkClicked,
            onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
            onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onEpisodeSwipe = onEpisodeSwipe,
            onEpisodeSelected = onEpisodeSelected,
            onAllEpisodeSelected = onAllEpisodeSelected,
            onInvertSelection = onInvertSelection,
            onSettingsClicked = onSettingsClicked,
            onSeasonClicked = onSeasonClicked,
            onClickContinueWatching = onContinueWatchingClicked,
        )
    } else {
        AnimeScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            episodeSwipeStartAction = episodeSwipeStartAction,
            episodeSwipeEndAction = episodeSwipeEndAction,
            showNextEpisodeAirTime = showNextEpisodeAirTime,
            alwaysUseExternalPlayer = alwaysUseExternalPlayer,
            navigateUp = navigateUp,
            onEpisodeClicked = onEpisodeClicked,
            onDownloadEpisode = onDownloadEpisode,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueWatching = onContinueWatching,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            changeAnimeSkipIntro = changeAnimeSkipIntro,
            onMigrateClicked = onMigrateClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiFillermarkClicked = onMultiFillermarkClicked,
            onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
            onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onEpisodeSwipe = onEpisodeSwipe,
            onEpisodeSelected = onEpisodeSelected,
            onAllEpisodeSelected = onAllEpisodeSelected,
            onInvertSelection = onInvertSelection,
            onSettingsClicked = onSettingsClicked,
            onSeasonClicked = onSeasonClicked,
            onClickContinueWatching = onContinueWatchingClicked,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimeScreenSmallImpl(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    navigateUp: () -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    changeAnimeSkipIntro: (() -> Unit)?,
    onSettingsClicked: (() -> Unit)?,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,

    // For episode swipe
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,

    // Episode selection
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onAllEpisodeSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,

    // Season clicked
    onSeasonClicked: (SeasonAnime) -> Unit,
    onClickContinueWatching: ((SeasonAnime) -> Unit)?,
) {
    val density = LocalDensity.current
    val offsetGridPaddingPx = with(density) { GRID_PADDING.roundToPx() }
    val gridSize = remember(state.anime) { state.anime.seasonDisplayGridSize }

    val itemListState = rememberLazyGridState()

    val seasons = remember(state) { state.processedSeasons }
    val episodes = remember(state) { state.processedEpisodes }
    val listItem = remember(state) { state.episodeListItems }
    val hasFilters = remember(state) {
        when (state.anime.fetchType) {
            FetchType.Seasons -> state.anime.seasonsFiltered()
            FetchType.Episodes -> state.anime.episodesFiltered()
        }
    }

    var toolbarHeight by remember { mutableIntStateOf(0) }

    val isAnySelected by remember {
        derivedStateOf {
            episodes.fastAny { it.selected }
        }
    }

    BackHandler(onBack = {
        if (isAnySelected) {
            onAllEpisodeSelected(false)
        } else {
            navigateUp()
        }
    })

    BoxWithConstraints {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { this@BoxWithConstraints.maxHeight.roundToPx() }
        // Same aurora the manga detail sits on, so switching mode keeps one surface.
        AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                val selectedEpisodeCount: Int = remember(episodes) {
                    episodes.count { it.selected }
                }
                val isFirstItemVisible by remember {
                    derivedStateOf { itemListState.firstVisibleItemIndex == 0 }
                }
                val isFirstItemScrolled by remember {
                    derivedStateOf { itemListState.firstVisibleItemScrollOffset > 0 }
                }
                val titleAlpha by animateFloatAsState(
                    if (!isFirstItemVisible) 1f else 0f,
                    label = "Top Bar Title",
                )
                val backgroundAlpha by animateFloatAsState(
                    if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                    label = "Top Bar Background",
                )
                EntryToolbar(
                    title = state.anime.title,
                    hasFilters = hasFilters,
                    navigateUp = navigateUp,
                    onClickFilter = onFilterClicked,
                    onClickShare = onShareClicked,
                    onClickDownload = onDownloadActionClicked,
                    onClickEditCategory = onEditCategoryClicked,
                    onClickRefresh = onRefresh,
                    onClickMigrate = onMigrateClicked,
                    onClickSettings = onSettingsClicked,
                    changeAnimeSkipIntro = changeAnimeSkipIntro,
                    actionModeCounter = selectedEpisodeCount,
                    onCancelActionMode = { onAllEpisodeSelected(false) },
                    onSelectAll = { onAllEpisodeSelected(true) },
                    onInvertSelection = { onInvertSelection() },
                    titleAlphaProvider = { titleAlpha },
                    backgroundAlphaProvider = { backgroundAlpha },
                    isManga = false,
                    modifier = Modifier.onSizeChanged { toolbarHeight = it.height },
                )
            },
            bottomBar = {
                val selectedEpisodes = remember(episodes) {
                    episodes.filter { it.selected }
                }
                SharedAnimeBottomActionMenu(
                    selected = selectedEpisodes,
                    onEpisodeClicked = onEpisodeClicked,
                    onMultiBookmarkClicked = onMultiBookmarkClicked,
                    onMultiFillermarkClicked = onMultiFillermarkClicked,
                    onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
                    onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
                    onDownloadEpisode = onDownloadEpisode,
                    onMultiDeleteClicked = onMultiDeleteClicked,
                    fillFraction = 1f,
                    alwaysUseExternalPlayer = alwaysUseExternalPlayer,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                val isFABVisible = remember(episodes) {
                    episodes.fastAny { !it.episode.seen } && !isAnySelected
                }
                SmallExtendedFloatingActionButton(
                    text = {
                        val isWatching = remember(state.episodes) {
                            state.episodes.fastAny { it.episode.seen }
                        }
                        Text(
                            text = stringResource(
                                if (isWatching) MR.strings.action_resume else MR.strings.action_start,
                            ),
                        )
                    },
                    icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = onContinueWatching,
                    expanded = itemListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = isFABVisible,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            val topPadding = contentPadding.calculateTopPadding()

            PullRefresh(
                refreshing = state.isRefreshingData,
                onRefresh = onRefresh,
                enabled = !isAnySelected,
                indicatorPadding = PaddingValues(top = topPadding),
            ) {
                val layoutDirection = LocalLayoutDirection.current
                FastScrollLazyVerticalGrid(
                    modifier = Modifier.fillMaxHeight(),
                    state = itemListState,
                    columns = if (gridSize == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(gridSize),
                    contentPadding = PaddingValues(
                        start = GRID_PADDING + contentPadding.calculateStartPadding(layoutDirection),
                        end = GRID_PADDING + contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                    item(
                        key = EntryScreenItem.INFO_BOX,
                        contentType = EntryScreenItem.INFO_BOX,
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        AnimeInfoBox(
                            isTabletUi = false,
                            appBarPadding = topPadding,
                            anime = state.anime,
                            sourceName = remember { state.source.getNameForAnimeInfo() },
                            isStubSource = remember { state.source is StubAnimeSource },
                            onCoverClick = onCoverClicked,
                            doSearch = onSearch,
                            modifier = Modifier.ignorePadding(offsetGridPaddingPx),
                        )
                    }

                    item(
                        key = EntryScreenItem.ACTION_ROW,
                        contentType = EntryScreenItem.ACTION_ROW,
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Column(modifier = Modifier.ignorePadding(offsetGridPaddingPx)) {
                            if (episodes.isNotEmpty()) {
                                val isWatching = remember(state.episodes) {
                                    state.episodes.fastAny { it.episode.seen }
                                }
                                AnimeWatchButton(
                                    isWatching = isWatching,
                                    onContinueWatching = onContinueWatching,
                                )
                            }
                            AnimeActionRow(
                                favorite = state.anime.favorite,
                                trackingCount = state.trackingCount,
                                nextUpdate = nextUpdate,
                                isUserIntervalMode = state.anime.fetchInterval < 0,
                                onAddToLibraryClicked = onAddToLibraryClicked,
                                onWebViewClicked = onWebViewClicked,
                                onWebViewLongClicked = onWebViewLongClicked,
                                onTrackingClicked = onTrackingClicked,
                                onEditIntervalClicked = onEditIntervalClicked,
                                onEditCategory = onEditCategoryClicked,
                            )
                        }
                    }

                    item(
                        key = EntryScreenItem.DESCRIPTION_WITH_TAG,
                        contentType = EntryScreenItem.DESCRIPTION_WITH_TAG,
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        ExpandableAnimeDescription(
                            defaultExpandState = state.isFromSource,
                            description = state.anime.description,
                            tagsProvider = { state.anime.genre },
                            onTagSearch = onTagSearch,
                            onCopyTagToClipboard = onCopyTagToClipboard,
                            modifier = Modifier.ignorePadding(offsetGridPaddingPx),
                        )
                    }

                    item(
                        key = EntryScreenItem.ITEM_HEADER,
                        contentType = EntryScreenItem.ITEM_HEADER,
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        val missingEpisodesCount = remember(episodes) {
                            episodes.map { it.episode.episodeNumber }.missingEntriesCount()
                        }
                        val missingSeasonsCount = remember(seasons) {
                            seasons.map { it.seasonAnime.anime.seasonNumber }.missingEntriesCount()
                        }
                        ItemHeader(
                            enabled = !isAnySelected,
                            itemCount = when (state.anime.fetchType) {
                                FetchType.Seasons -> seasons.size
                                FetchType.Episodes -> episodes.size
                            },
                            missingItemsCount = maxOf(missingEpisodesCount, missingSeasonsCount),
                            onClick = onFilterClicked,
                            isManga = false,
                            fetchType = state.anime.fetchType,
                            modifier = Modifier.ignorePadding(offsetGridPaddingPx),
                        )
                    }

                    when (state.anime.fetchType) {
                        FetchType.Seasons -> {
                            sharedSeasons(
                                anime = state.anime,
                                seasons = seasons,
                                containerHeight = containerHeightPx - toolbarHeight,
                                onSeasonClicked = onSeasonClicked,
                                onClickContinueWatching = onClickContinueWatching,
                                listItemModifier = Modifier.ignorePadding(offsetGridPaddingPx),
                            )
                        }

                        FetchType.Episodes -> {
                            if (state.airingTime > 0L) {
                                item(
                                    key = EntryScreenItem.AIRING_TIME,
                                    contentType = EntryScreenItem.AIRING_TIME,
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    // Handles the second by second countdown
                                    var timer by remember { mutableLongStateOf(state.airingTime) }
                                    LaunchedEffect(key1 = timer) {
                                        if (timer > 0L) {
                                            delay(1000L)
                                            timer -= 1000L
                                        }
                                    }
                                    if (timer > 0L &&
                                        showNextEpisodeAirTime &&
                                        state.anime.status.toInt() != SAnime.COMPLETED
                                    ) {
                                        NextEpisodeAiringListItem(
                                            title = stringResource(
                                                AYMR.strings.display_mode_episode,
                                                formatEpisodeNumber(state.airingEpisodeNumber),
                                            ),
                                            date = formatTime(state.airingTime, useDayFormat = true),
                                            modifier = Modifier.ignorePadding(offsetGridPaddingPx),
                                        )
                                    }
                                }
                            }

                            sharedEpisodeItems(
                                anime = state.anime,
                                episodes = listItem,
                                isAnyEpisodeSelected = episodes.fastAny { it.selected },
                                showSummaries = state.showSummaries,
                                showPreviews = state.showPreviews,
                                episodeSwipeStartAction = episodeSwipeStartAction,
                                episodeSwipeEndAction = episodeSwipeEndAction,
                                onEpisodeClicked = onEpisodeClicked,
                                onDownloadEpisode = onDownloadEpisode,
                                onEpisodeSelected = onEpisodeSelected,
                                onEpisodeSwipe = onEpisodeSwipe,
                                itemModifier = Modifier.ignorePadding(offsetGridPaddingPx),
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeScreenLargeImpl(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    navigateUp: () -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    changeAnimeSkipIntro: (() -> Unit)?,
    onSettingsClicked: (() -> Unit)?,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,

    // For swipe actions
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,

    // Episode selection
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onAllEpisodeSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,

    // Season clicked
    onSeasonClicked: (SeasonAnime) -> Unit,
    onClickContinueWatching: ((SeasonAnime) -> Unit)?,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val seasons = remember(state) { state.processedSeasons }
    val episodes = remember(state) { state.processedEpisodes }
    val listItem = remember(state) { state.episodeListItems }

    val isAnySelected by remember {
        derivedStateOf {
            episodes.fastAny { it.selected }
        }
    }

    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }
    val offsetGridPaddingPx = with(density) { GRID_PADDING.roundToPx() }
    val gridSize = remember(state.anime) { state.anime.seasonDisplayGridSize }

    val itemListState = rememberLazyGridState()
    val hasFilters = remember(state) {
        when (state.anime.fetchType) {
            FetchType.Seasons -> state.anime.seasonsFiltered()
            FetchType.Episodes -> state.anime.episodesFiltered()
        }
    }

    BackHandler(onBack = {
        if (isAnySelected) {
            onAllEpisodeSelected(false)
        } else {
            navigateUp()
        }
    })

    if (state.anime.fetchType == FetchType.Episodes) {
        KotoriTabletAnimeDetail(
            state = state,
            snackbarHostState = snackbarHostState,
            episodes = episodes,
            listItem = listItem,
            isAnySelected = isAnySelected,
            alwaysUseExternalPlayer = alwaysUseExternalPlayer,
            navigateUp = navigateUp,
            onEpisodeClicked = onEpisodeClicked,
            onDownloadEpisode = onDownloadEpisode,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onTrackingClicked = onTrackingClicked,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueWatching = onContinueWatching,
            onCoverClicked = onCoverClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onSettingsClicked = onSettingsClicked,
            onShareClicked = onShareClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onMigrateClicked = onMigrateClicked,
            onEditIntervalClicked = onEditIntervalClicked,
            changeAnimeSkipIntro = changeAnimeSkipIntro,
            onTagSearch = onTagSearch,
            onAllEpisodeSelected = onAllEpisodeSelected,
            onInvertSelection = onInvertSelection,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiFillermarkClicked = onMultiFillermarkClicked,
            onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
            onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onEpisodeSelected = onEpisodeSelected,
        )
        return
    }

    KotoriTabletSeasonDetail(
        state = state,
        snackbarHostState = snackbarHostState,
        seasons = seasons,
        navigateUp = navigateUp,
        onAddToLibraryClicked = onAddToLibraryClicked,
        onWebViewClicked = onWebViewClicked,
        onTrackingClicked = onTrackingClicked,
        onFilterButtonClicked = onFilterButtonClicked,
        onRefresh = onRefresh,
        onContinueWatching = onContinueWatching,
        onCoverClicked = onCoverClicked,
        onSettingsClicked = onSettingsClicked,
        onShareClicked = onShareClicked,
        onEditCategoryClicked = onEditCategoryClicked,
        onMigrateClicked = onMigrateClicked,
        onEditIntervalClicked = onEditIntervalClicked,
        onTagSearch = onTagSearch,
        onSeasonClicked = onSeasonClicked,
        onClickContinueWatching = onClickContinueWatching,
        isWatching = state.episodes.fastAny { it.episode.seen },
    )
}

/**
 * T2 · Chi tiết anime theo mùa — the same tablet composition as the episode detail: the key
 * visual and every action on the left, the season grid filling the right pane. Seasons keep
 * their own poster grid instead of the two-column row list, so the tab body is that grid.
 */
@Composable
private fun KotoriTabletSeasonDetail(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    seasons: List<AnimeSeasonItem>,
    navigateUp: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onTrackingClicked: (() -> Unit)?,
    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: () -> Unit,
    onCoverClicked: () -> Unit,
    onSettingsClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onTagSearch: (String) -> Unit,
    onSeasonClicked: (SeasonAnime) -> Unit,
    onClickContinueWatching: ((SeasonAnime) -> Unit)?,
    isWatching: Boolean,
) {
    val accent = AnimeAccent
    val anime = state.anime
    val sourceName = remember(state) { state.source.getNameForAnimeInfo() }
    val cover = remember(anime) {
        AnimeCover(
            animeId = anime.id,
            sourceId = anime.source,
            isAnimeFavorite = anime.favorite,
            url = anime.thumbnailUrl,
            lastModified = anime.coverLastModified,
        )
    }
    val gridSize = remember(anime) { anime.seasonDisplayGridSize }
    val seasonListState = rememberLazyGridState()

    BoxWithConstraints {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { this@BoxWithConstraints.maxHeight.roundToPx() }
        AuroraBackground {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { contentPadding ->
                PullRefresh(
                    refreshing = state.isRefreshingData,
                    onRefresh = onRefresh,
                    enabled = true,
                ) {
                    KotoriTabletDetailLayout(
                        modifier = Modifier.padding(contentPadding),
                        accent = accent,
                        title = anime.title,
                        coverData = cover,
                        chips = buildList {
                            add(KotoriDetailChip(animeStatusLabel(anime.status), highlighted = true))
                            add(KotoriDetailChip(sourceName))
                            add(KotoriDetailChip("${seasons.size} mùa"))
                        },
                        metaLine = listOfNotNull(
                            anime.author?.takeIf { it.isNotBlank() },
                            anime.artist?.takeIf { it.isNotBlank() && it != anime.author },
                        ).joinToString(" · "),
                        genres = remember(anime.genre) {
                            anime.genre.orEmpty().filter { it.isNotBlank() }
                        },
                        onGenreClick = onTagSearch,
                        rating = null,
                        trackerLine = state.trackingCount.takeIf { it > 0 }?.let { "Đã liên kết · $it dịch vụ" },
                        description = anime.description,
                        favorite = anime.favorite,
                        trackingCount = state.trackingCount,
                        ctaLabel = stringResource(
                            if (isWatching) MR.strings.action_resume else MR.strings.action_start,
                        ),
                        ctaIcon = Icons.Filled.PlayArrow,
                        onNavigateUp = navigateUp,
                        onCta = onContinueWatching,
                        onFavorite = onAddToLibraryClicked,
                        onTracking = { onTrackingClicked?.invoke() },
                        onDownload = null,
                        moreActions = buildList {
                            add(KotoriDetailMenuAction(stringResource(MR.strings.action_webview_refresh), onRefresh))
                            onEditCategoryClicked?.let {
                                add(KotoriDetailMenuAction(stringResource(MR.strings.action_edit_categories), it))
                            }
                            onMigrateClicked?.let {
                                add(KotoriDetailMenuAction(stringResource(MR.strings.action_migrate), it))
                            }
                            onShareClicked?.let {
                                add(KotoriDetailMenuAction(stringResource(MR.strings.action_share), it))
                            }
                            onEditIntervalClicked?.let {
                                add(KotoriDetailMenuAction(stringResource(MR.strings.action_set_interval), it))
                            }
                            onSettingsClicked?.let {
                                add(KotoriDetailMenuAction(stringResource(AYMR.strings.settings), it))
                            }
                        },
                        onMore = { onSettingsClicked?.invoke() ?: onFilterButtonClicked() },
                        onCoverClick = onCoverClicked,
                        tabs = listOf("${seasons.size} mùa", stringResource(MR.strings.action_web_view)),
                        activeTab = 0,
                        onSelectTab = { if (it == 1) onWebViewClicked?.invoke() },
                        onFilter = onFilterButtonClicked,
                        quickDownloadLabel = null,
                        onQuickDownload = null,
                        items = emptyList(),
                        tabContent = {
                            FastScrollLazyVerticalGrid(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 14.dp),
                                state = seasonListState,
                                columns = if (gridSize == 0) {
                                    GridCells.Adaptive(128.dp)
                                } else {
                                    GridCells.Fixed(gridSize)
                                },
                                horizontalArrangement = Arrangement.spacedBy(11.dp),
                                verticalArrangement = Arrangement.spacedBy(11.dp),
                                contentPadding = PaddingValues(bottom = 8.dp),
                            ) {
                                sharedSeasons(
                                    anime = anime,
                                    seasons = seasons,
                                    containerHeight = containerHeightPx,
                                    onSeasonClicked = onSeasonClicked,
                                    onClickContinueWatching = onClickContinueWatching,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * T2 · Chi tiết anime — tablet composition: full-height key visual on the left with
 * every action overlaid, tabs and a two-column episode grid on the right.
 */
@Composable
private fun KotoriTabletAnimeDetail(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    episodes: List<EpisodeList.Item>,
    listItem: List<EpisodeList>,
    isAnySelected: Boolean,
    alwaysUseExternalPlayer: Boolean,
    navigateUp: () -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onTrackingClicked: (() -> Unit)?,
    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: () -> Unit,
    onCoverClicked: () -> Unit,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onSettingsClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)? = null,
    onEditCategoryClicked: (() -> Unit)? = null,
    onMigrateClicked: (() -> Unit)? = null,
    onEditIntervalClicked: (() -> Unit)? = null,
    changeAnimeSkipIntro: (() -> Unit)? = null,
    onTagSearch: (String) -> Unit = {},
    onAllEpisodeSelected: (Boolean) -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
) {
    val accent = AnimeAccent
    val haptic = LocalHapticFeedback.current
    val anime = state.anime
    val sourceName = remember(state) { state.source.getNameForAnimeInfo() }
    val cover = remember(anime) {
        AnimeCover(
            animeId = anime.id,
            sourceId = anime.source,
            isAnimeFavorite = anime.favorite,
            url = anime.thumbnailUrl,
            lastModified = anime.coverLastModified,
        )
    }
    val isWatching = remember(state.episodes) { state.episodes.fastAny { it.episode.seen } }
    val selectedEpisodeCount = remember(episodes) { episodes.count { it.selected } }
    val detailItems = remember(listItem, isAnySelected) {
        listItem.filterIsInstance<EpisodeList.Item>().map { item ->
            val episode = item.episode
            val watchProgress = if (episode.totalSeconds > 0L && !episode.seen) {
                episode.lastSecondSeen.toFloat() / episode.totalSeconds
            } else {
                null
            }
            KotoriTabletDetailItem(
                key = "episode-${item.id}",
                badge = "T${formatEpisodeNumber(episode.episodeNumber)}",
                title = episode.name,
                subtitle = buildString {
                    when {
                        episode.seen -> append("đã xem")
                        watchProgress != null && watchProgress > 0f ->
                            append("xem dở ${(watchProgress * 100).toInt()}%")
                        else -> append("chưa xem")
                    }
                    when (item.downloadState) {
                        AnimeDownload.State.DOWNLOADED -> append(" · đã tải")
                        AnimeDownload.State.DOWNLOADING -> append(" · đang tải ${item.downloadProgress}%")
                        AnimeDownload.State.QUEUE -> append(" · trong hàng đợi")
                        else -> Unit
                    }
                },
                thumbData = episode.previewUrl ?: cover,
                progress = watchProgress?.takeIf { it > 0f },
                stateIcon = when (item.downloadState) {
                    AnimeDownload.State.DOWNLOADED -> Icons.Filled.DownloadDone
                    AnimeDownload.State.DOWNLOADING, AnimeDownload.State.QUEUE -> Icons.Filled.Downloading
                    AnimeDownload.State.ERROR -> Icons.Filled.ErrorOutline
                    else -> when {
                        episode.bookmark -> Icons.Filled.Bookmark
                        episode.seen -> Icons.Filled.CheckCircle
                        watchProgress != null && watchProgress > 0f -> Icons.Filled.PlayCircle
                        else -> Icons.Filled.Download
                    }
                },
                stateTint = when {
                    item.downloadState == AnimeDownload.State.DOWNLOADED -> KotoriColors.success
                    item.downloadState == AnimeDownload.State.ERROR -> KotoriColors.danger
                    item.downloadState != AnimeDownload.State.NOT_DOWNLOADED -> accent.light
                    episode.bookmark -> KotoriColors.warning
                    episode.seen -> KotoriColors.textFaint
                    watchProgress != null && watchProgress > 0f -> Color(0xFFF472B6)
                    else -> KotoriColors.textMuted
                },
                dimmed = episode.seen,
                highlighted = watchProgress != null && watchProgress > 0f,
                selected = item.selected,
                onClick = {
                    onEpisodeItemClick(
                        episodeItem = item,
                        isAnyEpisodeSelected = isAnySelected,
                        onToggleSelection = { onEpisodeSelected(item, !item.selected, true, false) },
                        onEpisodeClicked = onEpisodeClicked,
                    )
                },
                onLongClick = {
                    onEpisodeSelected(item, !item.selected, true, true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onStateClick = onDownloadEpisode?.let {
                    {
                        it(
                            listOf(item),
                            if (item.isDownloaded) {
                                EpisodeDownloadAction.DELETE
                            } else {
                                EpisodeDownloadAction.START
                            },
                        )
                    }
                },
            )
        }
    }

    AuroraBackground(accent = accent) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    val selectedEpisodes = remember(episodes) { episodes.filter { it.selected } }
                    SharedAnimeBottomActionMenu(
                        selected = selectedEpisodes,
                        onEpisodeClicked = onEpisodeClicked,
                        onMultiBookmarkClicked = onMultiBookmarkClicked,
                        onMultiFillermarkClicked = onMultiFillermarkClicked,
                        onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
                        onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
                        onDownloadEpisode = onDownloadEpisode,
                        onMultiDeleteClicked = onMultiDeleteClicked,
                        fillFraction = 0.5f,
                        alwaysUseExternalPlayer = alwaysUseExternalPlayer,
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            PullRefresh(
                refreshing = state.isRefreshingData,
                onRefresh = onRefresh,
                enabled = !isAnySelected,
            ) {
                KotoriTabletDetailLayout(
                    modifier = Modifier.padding(contentPadding),
                    accent = accent,
                    title = anime.title,
                    coverData = cover,
                    chips = buildList {
                        add(KotoriDetailChip(animeStatusLabel(anime.status), highlighted = true))
                        add(KotoriDetailChip(sourceName))
                        add(KotoriDetailChip("${episodes.size} tập"))
                    },
                    metaLine = listOfNotNull(
                        anime.author?.takeIf { it.isNotBlank() },
                        anime.artist?.takeIf { it.isNotBlank() && it != anime.author },
                    ).joinToString(" · "),
                    genres = remember(anime.genre) {
                        anime.genre.orEmpty().filter { it.isNotBlank() }
                    },
                    onGenreClick = onTagSearch,
                    rating = null,
                    trackerLine = state.trackingCount.takeIf { it > 0 }?.let { "Đã liên kết · $it dịch vụ" },
                    description = anime.description,
                    favorite = anime.favorite,
                    trackingCount = state.trackingCount,
                    ctaLabel = stringResource(
                        if (isWatching) MR.strings.action_resume else MR.strings.action_start,
                    ),
                    ctaIcon = Icons.Filled.PlayArrow,
                    onNavigateUp = navigateUp,
                    onCta = onContinueWatching,
                    onFavorite = onAddToLibraryClicked,
                    onTracking = { onTrackingClicked?.invoke() },
                    onDownload = null,
                    downloadMenu = onDownloadActionClicked?.let { downloadAction ->
                        { expanded, onDismiss ->
                            EntryDownloadDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = onDismiss,
                                onDownloadClicked = downloadAction,
                                isManga = false,
                            )
                        }
                    },
                    moreActions = buildList {
                        add(KotoriDetailMenuAction(stringResource(MR.strings.action_webview_refresh), onRefresh))
                        onEditCategoryClicked?.let {
                            add(KotoriDetailMenuAction(stringResource(MR.strings.action_edit_categories), it))
                        }
                        onMigrateClicked?.let {
                            add(KotoriDetailMenuAction(stringResource(MR.strings.action_migrate), it))
                        }
                        onShareClicked?.let {
                            add(KotoriDetailMenuAction(stringResource(MR.strings.action_share), it))
                        }
                        changeAnimeSkipIntro?.let {
                            add(KotoriDetailMenuAction(stringResource(AYMR.strings.action_change_intro_length), it))
                        }
                        onEditIntervalClicked?.let {
                            add(KotoriDetailMenuAction(stringResource(MR.strings.action_set_interval), it))
                        }
                        onSettingsClicked?.let {
                            add(KotoriDetailMenuAction(stringResource(AYMR.strings.settings), it))
                        }
                    },
                    onMore = { onSettingsClicked?.invoke() ?: onFilterButtonClicked() },
                    onCoverClick = onCoverClicked,
                    tabs = listOf("${episodes.size} tập", stringResource(MR.strings.action_web_view)),
                    activeTab = 0,
                    onSelectTab = { if (it == 1) onWebViewClicked?.invoke() },
                    onFilter = onFilterButtonClicked,
                    quickDownloadLabel = "Tải 5 tập kế".takeIf { onDownloadActionClicked != null },
                    onQuickDownload = onDownloadActionClicked?.let { { it(DownloadAction.NEXT_5_ITEMS) } },
                    selectionCount = selectedEpisodeCount,
                    onSelectAll = { onAllEpisodeSelected(true) },
                    onInvertSelection = onInvertSelection,
                    items = detailItems,
                )
            }
        }
    }
}

@Composable
private fun animeStatusLabel(status: Long): String = when (status) {
    SAnime.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
    SAnime.COMPLETED.toLong() -> stringResource(MR.strings.completed)
    SAnime.LICENSED.toLong() -> stringResource(MR.strings.licensed)
    SAnime.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
    SAnime.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
    SAnime.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
    else -> stringResource(MR.strings.unknown)
}

@Composable
private fun SharedAnimeBottomActionMenu(
    selected: List<EpisodeList.Item>,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Episode>) -> Unit,
    fillFraction: Float,
    alwaysUseExternalPlayer: Boolean,
    modifier: Modifier = Modifier,
) {
    EntryBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.episode }, true)
        }.takeIf { selected.fastAny { !it.episode.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.episode }, false)
        }.takeIf { selected.fastAll { it.episode.bookmark } },
        onFillermarkClicked = {
            onMultiFillermarkClicked.invoke(selected.fastMap { it.episode }, true)
        }.takeIf { selected.fastAny { !it.episode.fillermark } },
        onRemoveFillermarkClicked = {
            onMultiFillermarkClicked.invoke(selected.fastMap { it.episode }, false)
        }.takeIf { selected.fastAll { it.episode.fillermark } },
        onMarkAsViewedClicked = {
            onMultiMarkAsSeenClicked(selected.fastMap { it.episode }, true)
        }.takeIf { selected.fastAny { !it.episode.seen } },
        onMarkAsUnviewedClicked = {
            onMultiMarkAsSeenClicked(selected.fastMap { it.episode }, false)
        }.takeIf { selected.fastAny { it.episode.seen || it.episode.lastSecondSeen > 0L } },
        onMarkPreviousAsViewedClicked = {
            onMarkPreviousAsSeenClicked(selected[0].episode)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadEpisode!!(selected.toList(), EpisodeDownloadAction.START)
        }.takeIf {
            onDownloadEpisode != null && selected.fastAny { it.downloadState != AnimeDownload.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.episode })
        }.takeIf {
            onDownloadEpisode != null && selected.fastAny { it.downloadState == AnimeDownload.State.DOWNLOADED }
        },
        onExternalClicked = {
            onEpisodeClicked(selected.fastMap { it.episode }.first(), true)
        }.takeIf { !alwaysUseExternalPlayer && selected.size == 1 },
        onInternalClicked = {
            onEpisodeClicked(selected.fastMap { it.episode }.first(), true)
        }.takeIf { alwaysUseExternalPlayer && selected.size == 1 },
        isManga = false,
    )
}

private fun LazyGridScope.sharedSeasons(
    anime: Anime,
    seasons: List<AnimeSeasonItem>,
    containerHeight: Int,
    onSeasonClicked: (SeasonAnime) -> Unit,
    onClickContinueWatching: ((SeasonAnime) -> Unit)?,
    listItemModifier: Modifier = Modifier,
) {
    items(
        items = seasons,
        key = { season -> season.seasonAnime.anime },
        span = { GridItemSpan(if (anime.seasonDisplayGridMode == SeasonDisplayMode.List) maxLineSpan else 1) },
    ) { item ->
        AnimeSeasonListItem(
            anime = anime,
            item = item,
            containerHeight = containerHeight,
            onSeasonClicked = onSeasonClicked,
            onClickContinueWatching = onClickContinueWatching,
            listItemModifier = listItemModifier,
        )
    }
}

private fun LazyGridScope.sharedEpisodeItems(
    anime: Anime,
    episodes: List<EpisodeList>,
    isAnyEpisodeSelected: Boolean,
    showSummaries: Boolean,
    showPreviews: Boolean,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,
    itemModifier: Modifier = Modifier,
) {
    items(
        items = episodes,
        key = { episodeItem ->
            when (episodeItem) {
                is EpisodeList.MissingCount -> "missing-count-${episodeItem.id}"
                is EpisodeList.Item -> "episode-${episodeItem.id}"
            }
        },
        contentType = { EntryScreenItem.ITEM },
        span = { GridItemSpan(maxLineSpan) },
    ) { episodeItem ->
        val haptic = LocalHapticFeedback.current

        when (episodeItem) {
            is EpisodeList.MissingCount -> {
                MissingItemCountListItem(
                    count = episodeItem.count,
                    modifier = itemModifier,
                )
            }
            is EpisodeList.Item -> {
                AnimeEpisodeListItem(
                    title = if (anime.displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                        stringResource(
                            AYMR.strings.display_mode_episode,
                            formatEpisodeNumber(episodeItem.episode.episodeNumber),
                        )
                    } else {
                        episodeItem.episode.name
                    },
                    date = relativeDateTimeText(episodeItem.episode.dateUpload),
                    watchProgress = episodeItem.episode.lastSecondSeen
                        .takeIf { !episodeItem.episode.seen && it > 0L }
                        ?.let {
                            stringResource(
                                AYMR.strings.episode_progress,
                                formatTime(it),
                                formatTime(episodeItem.episode.totalSeconds),
                            )
                        },
                    watchProgressFraction = episodeItem.episode.let { ep ->
                        if (!ep.seen && ep.totalSeconds > 0L && ep.lastSecondSeen > 0L) {
                            (ep.lastSecondSeen.toFloat() / ep.totalSeconds).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                    },
                    scanlator = episodeItem.episode.scanlator.takeIf { !it.isNullOrBlank() },
                    summary = episodeItem.episode.summary.takeIf { !it.isNullOrBlank() && showSummaries },
                    previewUrl = episodeItem.episode.previewUrl.takeIf { !it.isNullOrBlank() && showPreviews },
                    seen = episodeItem.episode.seen,
                    bookmark = episodeItem.episode.bookmark,
                    fillermark = episodeItem.episode.fillermark,
                    selected = episodeItem.selected,
                    isAnyEpisodeSelected = isAnyEpisodeSelected,
                    downloadIndicatorEnabled = !isAnyEpisodeSelected && !anime.isLocal(),
                    downloadStateProvider = { episodeItem.downloadState },
                    downloadProgressProvider = { episodeItem.downloadProgress },
                    episodeSwipeStartAction = episodeSwipeStartAction,
                    episodeSwipeEndAction = episodeSwipeEndAction,
                    onLongClick = {
                        onEpisodeSelected(episodeItem, !episodeItem.selected, true, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        onEpisodeItemClick(
                            episodeItem = episodeItem,
                            isAnyEpisodeSelected = isAnyEpisodeSelected,
                            onToggleSelection = { onEpisodeSelected(episodeItem, !episodeItem.selected, true, false) },
                            onEpisodeClicked = onEpisodeClicked,
                        )
                    },
                    onDownloadClick = if (onDownloadEpisode != null) {
                        { onDownloadEpisode(listOf(episodeItem), it) }
                    } else {
                        null
                    },
                    onEpisodeSwipe = {
                        onEpisodeSwipe(episodeItem, it)
                    },
                    modifier = itemModifier,
                )
            }
        }
    }
}

private fun onEpisodeItemClick(
    episodeItem: EpisodeList.Item,
    isAnyEpisodeSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
) {
    when {
        episodeItem.selected -> onToggleSelection(false)
        isAnyEpisodeSelected -> onToggleSelection(true)
        else -> onEpisodeClicked(episodeItem.episode, false)
    }
}

private fun formatTime(milliseconds: Long, useDayFormat: Boolean = false): String {
    return if (useDayFormat) {
        String.format(
            "Airing in %02dd %02dh %02dm %02ds",
            TimeUnit.MILLISECONDS.toDays(milliseconds),
            TimeUnit.MILLISECONDS.toHours(milliseconds) -
                TimeUnit.DAYS.toHours(TimeUnit.MILLISECONDS.toDays(milliseconds)),
            TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    } else if (milliseconds > 3600000L) {
        String.format(
            "%d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(milliseconds),
            TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    } else {
        String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(milliseconds),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    }
}

private val GRID_PADDING = 14.dp
private fun Modifier.ignorePadding(gridPadding: Int) = layout { measurable, constraints ->
    val looseConstraints = constraints.offset(gridPadding * 2, 0)
    val placeable = measurable.measure(looseConstraints)

    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}
