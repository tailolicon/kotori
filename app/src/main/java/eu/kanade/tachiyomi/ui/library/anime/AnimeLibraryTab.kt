package eu.kanade.tachiyomi.ui.library.anime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.EntryDownloadDropdownMenu
import eu.kanade.presentation.library.DeleteLibraryEntryDialog
import eu.kanade.presentation.library.anime.AnimeLibraryContent
import eu.kanade.presentation.library.anime.AnimeLibrarySettingsDialog
import eu.kanade.presentation.library.components.KotoriModeSwitcher
import eu.kanade.presentation.library.components.KotoriResumeHeroCard
import eu.kanade.presentation.library.components.KotoriTabletHero
import eu.kanade.presentation.library.components.KotoriTabletLibraryLayout
import eu.kanade.presentation.library.components.KotoriTabletLibraryTile
import eu.kanade.presentation.library.components.KotoriWordmark
import eu.kanade.presentation.library.components.rememberKotoriAiringToday
import eu.kanade.presentation.library.components.rememberKotoriResumeItems
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.theme.kotori.GradientButton
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriEmptyState
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSearchField
import eu.kanade.presentation.theme.kotori.KotoriSelectionBar
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.isKotoriTablet
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.category.anime.AnimeCategoryScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import androidx.compose.foundation.layout.PaddingValues
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

data object AnimeLibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { AnimeLibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { AnimeLibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val activeMode by uiPreferences.activeMediaMode.changes()
            .collectAsState(initial = uiPreferences.activeMediaMode.get())
        val lastWatched by produceState<AnimeHistoryWithRelations?>(initialValue = null) {
            Injekt.get<GetAnimeHistory>().subscribe("").collectLatest { value = it.firstOrNull() }
        }

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = AnimeLibraryUpdateJob.startNow(context, category)
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        suspend fun openEpisode(episode: Episode) {
            val playerPreferences: PlayerPreferences by injectLazy()
            val extPlayer = playerPreferences.alwaysUseExternalPlayer().get()
            MainActivity.startPlayerActivity(context, episode.animeId, episode.id, extPlayer)
        }

        val onContinueWatching: (LibraryAnime) -> Unit = {
            scope.launchIO {
                val episode = screenModel.getNextUnseenEpisode(it.anime)
                if (episode != null) {
                    openEpisode(episode)
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(AYMR.strings.no_next_episode))
                }
            }
        }

        val activeCategory = state.categories.getOrNull(screenModel.activeCategoryIndex)

        val tabletUi = isKotoriTablet()
        val resumeItems = rememberKotoriResumeItems(
            mode = activeMode,
            onOpenManga = { navigator.push(MangaScreen(it)) },
            onOpenAnime = { navigator.push(AnimeScreen(it)) },
        )
        val airingItems = rememberKotoriAiringToday(
            onOpenAnime = { navigator.push(AnimeScreen(it)) },
        )

        KotoriScreenScaffold(
            header = {
                if (!tabletUi) {
                    KotoriHeader(
                        titleContent = { KotoriWordmark() },
                        actions = {
                            KotoriHeaderAction(
                                icon = Icons.Filled.Search,
                                contentDescription = stringResource(MR.strings.action_search),
                                onClick = {
                                    if (state.searchQuery == null) screenModel.search("") else screenModel.search(null)
                                },
                            )
                            KotoriHeaderAction(
                                icon = Icons.Filled.Tune,
                                contentDescription = stringResource(MR.strings.action_filter),
                                onClick = screenModel::showSettingsDialog,
                                tint = if (state.hasActiveFilters) {
                                    KotoriTheme.accent.light
                                } else {
                                    KotoriColors.textPrimary.copy(alpha = 0.85f)
                                },
                            )
                            Box {
                                var menuOpen by remember { mutableStateOf(false) }
                                KotoriHeaderAction(
                                    icon = Icons.Filled.MoreVert,
                                    contentDescription = null,
                                    onClick = { menuOpen = true },
                                )
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(MR.strings.action_update_library)) },
                                        onClick = {
                                            menuOpen = false
                                            onClickRefresh(null)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(MR.strings.action_update_category)) },
                                        onClick = {
                                            menuOpen = false
                                            onClickRefresh(activeCategory)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(MR.strings.action_open_random_manga)) },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                val randomItem = screenModel.getRandomAnimelibItemForCurrentCategory()
                                                if (randomItem != null) {
                                                    navigator.push(AnimeScreen(randomItem.libraryAnime.anime.id))
                                                } else {
                                                    snackbarHostState.showSnackbar(
                                                        context.stringResource(MR.strings.information_no_entries_found),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                    if (state.searchQuery != null) {
                        KotoriSearchField(
                            value = state.searchQuery.orEmpty(),
                            onValueChange = screenModel::search,
                            placeholder = "Tìm trong thư viện…",
                            autoFocus = true,
                            onClear = { screenModel.search("") },
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp),
                        )
                    }
                }
            },
            bottomBar = {
                if (state.selectionMode) {
                    KotoriSelectionBar(count = state.selection.size) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.SelectAll,
                            contentDescription = stringResource(MR.strings.action_select_all),
                            onClick = { screenModel.selectAll(screenModel.activeCategoryIndex) },
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.FlipToBack,
                            contentDescription = stringResource(MR.strings.action_select_inverse),
                            onClick = { screenModel.invertSelection(screenModel.activeCategoryIndex) },
                        )
                        KotoriHeaderAction(
                            icon = Icons.AutoMirrored.Filled.Label,
                            contentDescription = stringResource(MR.strings.action_move_category),
                            onClick = screenModel::openChangeCategoryDialog,
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.DoneAll,
                            contentDescription = stringResource(MR.strings.action_mark_as_read),
                            onClick = { screenModel.markSeenSelection(true) },
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.RemoveDone,
                            contentDescription = stringResource(MR.strings.action_mark_as_unread),
                            onClick = { screenModel.markSeenSelection(false) },
                        )
                        if (state.selection.fastAll { !it.anime.isLocal() }) {
                            Box {
                                var dlMenu by remember { mutableStateOf(false) }
                                KotoriHeaderAction(
                                    icon = Icons.Filled.Download,
                                    contentDescription = stringResource(MR.strings.manga_download),
                                    onClick = { dlMenu = true },
                                )
                                EntryDownloadDropdownMenu(
                                    expanded = dlMenu,
                                    onDismissRequest = { dlMenu = false },
                                    onDownloadClicked = {
                                        dlMenu = false
                                        screenModel.runDownloadActionSelection(it)
                                    },
                                    isManga = false,
                                )
                            }
                        }
                        KotoriHeaderAction(
                            icon = Icons.Filled.SwapCalls,
                            contentDescription = stringResource(MR.strings.migrate),
                            onClick = {
                                val first = state.selection.firstOrNull()
                                screenModel.clearSelection()
                                if (first != null) navigator.push(MigrateAnimeSearchScreen(first.anime.id))
                            },
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            onClick = screenModel::openDeleteAnimeDialog,
                            tint = KotoriColors.danger,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                tabletUi -> {
                    val displayMode = remember(screenModel) { screenModel.getDisplayMode() }
                    val items = state.getAnimelibItemsByPage(screenModel.activeCategoryIndex)
                    val history = lastWatched
                    val heroItem = history?.let { h ->
                        state.library.values.flatten().firstOrNull { it.libraryAnime.anime.id == h.animeId }
                    }
                    val selectedIds = state.selection.map { it.id }.toSet()
                    val searching = !state.searchQuery.isNullOrEmpty() || state.hasActiveFilters
                    var isRefreshing by remember { mutableStateOf(false) }
                    PullRefresh(
                        refreshing = isRefreshing,
                        enabled = !state.selectionMode,
                        onRefresh = {
                            if (!onClickRefresh(null)) return@PullRefresh
                            scope.launch {
                                // Fake refresh status but hide it after a second as it's a long running task
                                isRefreshing = true
                                delay(1.seconds)
                                isRefreshing = false
                            }
                        },
                        // Drop the spinner into the grid instead of on top of the search bar.
                        indicatorPadding = PaddingValues(top = KotoriTabletTokens.pullIndicatorInset),
                    ) {
                        KotoriTabletLibraryLayout(
                            modifier = Modifier.padding(contentPadding),
                            activeMode = activeMode,
                            onSelectMode = { uiPreferences.activeMediaMode.set(it) },
                            searchQuery = state.searchQuery.orEmpty(),
                            onSearchChange = screenModel::search,
                            onClearSearch = { screenModel.search("") },
                            onOpenFilters = screenModel::showSettingsDialog,
                            filtersActive = state.hasActiveFilters,
                            onRefresh = { onClickRefresh(null) },
                            onRefreshAll = { onClickRefresh(null) },
                            onRefreshCategory = { onClickRefresh(activeCategory) },
                            onOpenRandom = {
                                scope.launch {
                                    val randomItem = screenModel.getRandomAnimelibItemForCurrentCategory()
                                    if (randomItem != null) {
                                        navigator.push(AnimeScreen(randomItem.libraryAnime.anime.id))
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            context.stringResource(MR.strings.information_no_entries_found),
                                        )
                                    }
                                }
                            },
                            displayMode = displayMode.value,
                            onCycleDisplayMode = {
                                val order = listOf(
                                    LibraryDisplayMode.CompactGrid,
                                    LibraryDisplayMode.ComfortableGrid,
                                    LibraryDisplayMode.CoverOnlyGrid,
                                    LibraryDisplayMode.List,
                                )
                                displayMode.value = order[(order.indexOf(displayMode.value) + 1) % order.size]
                            },
                            // A single unnamed system category is not a choice, so the chip row
                            // would only be showing the word "Default" with nothing to switch to.
                            categories = state.categories
                                .takeIf { it.size > 1 || it.none(Category::isSystemCategory) }
                                .orEmpty()
                                .map { it.visualName },
                            categoryCounts = state.categories.map { state.getAnimeCountForCategory(it) },
                            activeCategoryIndex = screenModel.activeCategoryIndex,
                            onSelectCategory = { screenModel.activeCategoryIndex = it },
                            title = stringResource(MR.strings.label_library),
                            subtitle = "${items.size} bộ · sắp theo cập nhật gần nhất",
                            tiles = items.map { item ->
                                val anime = item.libraryAnime.anime
                                KotoriTabletLibraryTile(
                                    id = anime.id,
                                    title = anime.title,
                                    statusLine = "Tập ${item.libraryAnime.totalCount} · ${
                                        animeLibraryStatusLabel(anime.status)
                                    }",
                                    coverData = AnimeCover(
                                        animeId = anime.id,
                                        sourceId = anime.source,
                                        isAnimeFavorite = anime.favorite,
                                        url = anime.thumbnailUrl,
                                        lastModified = anime.coverLastModified,
                                    ),
                                    unreadCount = item.unseenCount.coerceAtLeast(0),
                                    downloaded = item.downloadCount > 0,
                                    selected = anime.id in selectedIds,
                                    isLocal = item.isLocal,
                                    language = item.sourceLanguage.takeIf { it.isNotBlank() },
                                )
                            },
                            onClickTile = { id ->
                                val entry = items.firstOrNull { it.libraryAnime.anime.id == id }
                                if (state.selectionMode && entry != null) {
                                    screenModel.toggleSelection(entry.libraryAnime)
                                } else {
                                    navigator.push(AnimeScreen(id))
                                }
                            },
                            onLongClickTile = { id ->
                                items.firstOrNull { it.libraryAnime.anime.id == id }?.let {
                                    screenModel.toggleRangeSelection(it.libraryAnime)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            hero = if (history != null && heroItem != null) {
                                val libraryAnime = heroItem.libraryAnime
                                KotoriTabletHero(
                                    title = history.title,
                                    meta = "Tập ${formatEpisodeNumber(history.episodeNumber)}" +
                                        " · đã xem ${libraryAnime.seenCount}/${libraryAnime.totalCount}",
                                    progress = if (libraryAnime.totalCount > 0) {
                                        libraryAnime.seenCount.toFloat() / libraryAnime.totalCount
                                    } else {
                                        0f
                                    },
                                    coverData = history.coverData,
                                    onClick = { navigator.push(AnimeScreen(history.animeId)) },
                                    onResume = { onContinueWatching(libraryAnime) },
                                )
                            } else {
                                null
                            },
                            resumeItems = resumeItems,
                            airingItems = airingItems,
                            emptyContent = {
                                if (searching) {
                                    KotoriEmptyState(
                                        title = "Không có kết quả",
                                        hint = "Thử từ khóa khác hoặc tìm trên mọi nguồn",
                                        actions = {
                                            GradientButton(
                                                onClick = {
                                                    navigator.push(
                                                        GlobalAnimeSearchScreen(state.searchQuery.orEmpty()),
                                                    )
                                                },
                                            ) {
                                                Text(
                                                    text = "Tìm trên mọi nguồn",
                                                    color = KotoriTheme.accent.onAccent,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        },
                                    )
                                } else {
                                    val handler = LocalUriHandler.current
                                    KotoriEmptyState(
                                        title = "Thư viện trống",
                                        hint = "Thêm anime từ tab Duyệt",
                                        actions = {
                                            GradientButton(onClick = { handler.openUri(GETTING_STARTED_URL) }) {
                                                Text(
                                                    text = stringResource(MR.strings.getting_started_guide),
                                                    color = KotoriTheme.accent.onAccent,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    Column(Modifier.padding(contentPadding)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                        ) {
                            KotoriModeSwitcher(
                                active = activeMode,
                                onSelect = { uiPreferences.activeMediaMode.set(it) },
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                        KotoriEmptyState(
                            title = "Thư viện trống",
                            hint = "Thêm anime từ tab Duyệt",
                            actions = {
                                GradientButton(onClick = { handler.openUri(GETTING_STARTED_URL) }) {
                                    Text(
                                        text = stringResource(MR.strings.getting_started_guide),
                                        color = KotoriTheme.accent.onAccent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                }
                            },
                        )
                    }
                }
                else -> {
                    AnimeLibraryContent(
                        categories = state.categories,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = { screenModel.activeCategoryIndex },
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = { screenModel.activeCategoryIndex = it },
                        onAnimeClicked = { navigator.push(AnimeScreen(it)) },
                        onContinueWatchingClicked = onContinueWatching.takeIf { state.showAnimeContinueButton },
                        onToggleSelection = screenModel::toggleSelection,
                        onToggleRangeSelection = {
                            screenModel.toggleRangeSelection(it)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = onClickRefresh,
                        onGlobalSearchClicked = {
                            navigator.push(GlobalAnimeSearchScreen(screenModel.state.value.searchQuery ?: ""))
                        },
                        getNumberOfAnimeForCategory = { state.getAnimeCountForCategory(it) },
                        getDisplayMode = { screenModel.getDisplayMode() },
                        getColumnsForOrientation = { screenModel.getColumnsPreferenceForCurrentOrientation(it) },
                        getAnimeLibraryForPage = { state.getAnimelibItemsByPage(it) },
                        topContent = {
                            KotoriModeSwitcher(
                                active = activeMode,
                                onSelect = { uiPreferences.activeMediaMode.set(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp)
                                    .padding(top = 14.dp),
                            )
                        },
                        header = run {
                            val history = lastWatched
                            val heroItem = history?.let { h ->
                                state.library.values.flatten()
                                    .firstOrNull { it.libraryAnime.anime.id == h.animeId }
                            }
                            if (history != null && heroItem != null) {
                                {
                                    val libraryAnime = heroItem.libraryAnime
                                    KotoriResumeHeroCard(
                                        mode = activeMode,
                                        title = history.title,
                                        meta = "Tập ${formatEpisodeNumber(history.episodeNumber)}" +
                                            " · đã xem ${libraryAnime.seenCount}/${libraryAnime.totalCount}",
                                        progress = if (libraryAnime.totalCount > 0) {
                                            libraryAnime.seenCount.toFloat() / libraryAnime.totalCount
                                        } else {
                                            0f
                                        },
                                        coverData = history.coverData,
                                        onClick = { navigator.push(AnimeScreen(history.animeId)) },
                                        onResume = { onContinueWatching(libraryAnime) },
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                    )
                                }
                            } else {
                                null
                            }
                        },
                    )
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is AnimeLibraryScreenModel.Dialog.SettingsSheet -> run {
                AnimeLibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = activeCategory,
                )
            }
            is AnimeLibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(AnimeCategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setAnimeCategories(dialog.anime, include, exclude)
                    },
                )
            }
            is AnimeLibraryScreenModel.Dialog.DeleteAnime -> {
                DeleteLibraryEntryDialog(
                    containsLocalEntry = dialog.anime.any(Anime::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteAnime, deleteEpisode ->
                        screenModel.removeAnimes(dialog.anime, deleteAnime, deleteEpisode)
                        screenModel.clearSelection()
                    },
                    isManga = false,
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    private val requestSettingsSheetEvent = Channel<Unit>()
    suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}

@Composable
private fun animeLibraryStatusLabel(status: Long): String = when (status) {
    SAnime.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
    SAnime.COMPLETED.toLong() -> stringResource(MR.strings.completed)
    SAnime.LICENSED.toLong() -> stringResource(MR.strings.licensed)
    SAnime.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
    SAnime.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
    SAnime.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
    else -> stringResource(MR.strings.unknown)
}
