package eu.kanade.tachiyomi.ui.library

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
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.KotoriModeSwitcher
import eu.kanade.presentation.library.components.KotoriResumeHeroCard
import eu.kanade.presentation.library.components.KotoriWordmark
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.manga.DownloadAction
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
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import mihon.feature.localmedia.LocalMediaColumn
import mihon.feature.localmedia.LocalMediaDetailScreen
import mihon.feature.localmedia.LocalMediaEntry
import mihon.feature.localmedia.LocalMediaSectionLabel
import mihon.feature.localmedia.OnlineMediaSectionLabel
import mihon.feature.localmedia.loadLocalMedia
import mihon.feature.mediasource.fetchOnlineCatalog
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object LibraryTab : Tab {

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

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val activeMode by uiPreferences.activeMediaMode.changes()
            .collectAsState(initial = uiPreferences.activeMediaMode.get())
        val lastRead by produceState<HistoryWithRelations?>(initialValue = null) {
            Injekt.get<GetHistory>().subscribe("").collectLatest { value = it.firstOrNull() }
        }

        val onContinueReading: (LibraryManga) -> Unit = {
            scope.launchIO {
                val chapter = screenModel.getNextUnreadChapter(it.manga)
                if (chapter != null) {
                    context.startActivity(
                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                    )
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = LibraryUpdateJob.startNow(context, category)
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

        KotoriScreenScaffold(
            header = {
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
                                        onClickRefresh(state.activeCategory)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.action_open_random_manga)) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            val randomItem = screenModel.getRandomLibraryItemForCurrentCategory()
                                            if (randomItem != null) {
                                                navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
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
            },
            bottomBar = {
                if (state.selectionMode) {
                    KotoriSelectionBar(count = state.selection.size) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.SelectAll,
                            contentDescription = stringResource(MR.strings.action_select_all),
                            onClick = screenModel::selectAll,
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.FlipToBack,
                            contentDescription = stringResource(MR.strings.action_select_inverse),
                            onClick = screenModel::invertSelection,
                        )
                        KotoriHeaderAction(
                            icon = Icons.AutoMirrored.Filled.Label,
                            contentDescription = stringResource(MR.strings.action_move_category),
                            onClick = screenModel::openChangeCategoryDialog,
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.DoneAll,
                            contentDescription = stringResource(MR.strings.action_mark_as_read),
                            onClick = { screenModel.markReadSelection(true) },
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.RemoveDone,
                            contentDescription = stringResource(MR.strings.action_mark_as_unread),
                            onClick = { screenModel.markReadSelection(false) },
                        )
                        if (state.selectedManga.fastAll { !it.isLocal() }) {
                            Box {
                                var dlMenu by remember { mutableStateOf(false) }
                                KotoriHeaderAction(
                                    icon = Icons.Filled.Download,
                                    contentDescription = stringResource(MR.strings.manga_download),
                                    onClick = { dlMenu = true },
                                )
                                DownloadDropdownMenu(
                                    expanded = dlMenu,
                                    onDismissRequest = { dlMenu = false },
                                    onDownloadClicked = {
                                        dlMenu = false
                                        screenModel.performDownloadAction(it)
                                    },
                                )
                            }
                        }
                        KotoriHeaderAction(
                            icon = Icons.Filled.SwapCalls,
                            contentDescription = stringResource(MR.strings.migrate),
                            onClick = {
                                val selection = state.selection
                                screenModel.clearSelection()
                                navigator.push(MigrationConfigScreen(selection))
                            },
                        )
                        KotoriHeaderAction(
                            icon = Icons.Filled.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            onClick = screenModel::openDeleteMangaDialog,
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
                activeMode != MediaType.MANGA -> {
                    // Local folders under the storage root + online media sources
                    // (built-in reference sources; extensions register more).
                    val storageManager = remember { Injekt.get<StorageManager>() }
                    val localEntries by produceState(initialValue = emptyList<LocalMediaEntry>(), activeMode) {
                        value = loadLocalMedia(storageManager, activeMode)
                    }
                    val onlineEntries by produceState(initialValue = emptyList<LocalMediaEntry>(), activeMode) {
                        value = fetchOnlineCatalog(activeMode).flatMap { (source, entries) ->
                            entries.map { it.copy(sourceName = source.name) }
                        }
                    }
                    Column(Modifier.padding(contentPadding)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            KotoriModeSwitcher(
                                active = activeMode,
                                onSelect = { uiPreferences.activeMediaMode.set(it) },
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                        if (localEntries.isEmpty() && onlineEntries.isEmpty()) {
                            KotoriEmptyState(
                                title = if (activeMode == MediaType.ANIME) {
                                    "Chưa có nội dung Anime"
                                } else {
                                    "Chưa có nội dung Novel"
                                },
                                hint = if (activeMode == MediaType.ANIME) {
                                    "Chép video vào thư mục Kotori/anime/<tên phim>/ hoặc cài tiện ích Anime ở tab Duyệt"
                                } else {
                                    "Chép file .txt vào thư mục Kotori/novel/<tên truyện>/ hoặc cài tiện ích Novel ở tab Duyệt"
                                },
                            )
                        } else {
                            val openEntry: (LocalMediaEntry) -> Unit = { entry ->
                                navigator.push(LocalMediaDetailScreen(entry))
                            }
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            ) {
                                if (localEntries.isNotEmpty()) {
                                    LocalMediaSectionLabel(mediaType = activeMode)
                                    LocalMediaColumn(
                                        entries = localEntries,
                                        mediaType = activeMode,
                                        onClickEntry = openEntry,
                                    )
                                }
                                if (onlineEntries.isNotEmpty()) {
                                    OnlineMediaSectionLabel(mediaType = activeMode)
                                    LocalMediaColumn(
                                        entries = onlineEntries,
                                        mediaType = activeMode,
                                        onClickEntry = openEntry,
                                    )
                                }
                            }
                        }
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
                            hint = "Thêm truyện từ tab Duyệt",
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
                    LibraryContent(
                        categories = state.displayedCategories,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = state.coercedActiveCategoryIndex,
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                        topContent = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                KotoriModeSwitcher(
                                    active = activeMode,
                                    onSelect = { uiPreferences.activeMediaMode.set(it) },
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                                val heroItem = lastRead?.let { history ->
                                    state.libraryData.favoritesById[history.mangaId]
                                }
                                if (lastRead != null && heroItem != null) {
                                    val history = lastRead ?: return@Column
                                    val libraryManga = heroItem.libraryManga
                                    KotoriResumeHeroCard(
                                        mode = activeMode,
                                        title = history.title,
                                        meta = "Chương ${formatChapterNumber(history.chapterNumber)}" +
                                            " · đã đọc ${libraryManga.readCount}/${libraryManga.totalChapters}",
                                        progress = if (libraryManga.totalChapters > 0) {
                                            libraryManga.readCount.toFloat() / libraryManga.totalChapters
                                        } else {
                                            0f
                                        },
                                        coverData = history.coverData,
                                        onClick = { navigator.push(MangaScreen(history.mangaId)) },
                                        onResume = { onContinueReading(libraryManga) },
                                    )
                                }
                            }
                        },
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            onContinueReading(it)
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = screenModel::toggleSelection,
                        onToggleRangeSelection = { category, manga ->
                            screenModel.toggleRangeSelection(category, manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = { onClickRefresh(state.activeCategory) },
                        onGlobalSearchClicked = {
                            navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                        },
                        getItemCountForCategory = { state.getItemCountForCategory(it) },
                        getDisplayMode = { screenModel.getDisplayMode() },
                        getColumnsForOrientation = { screenModel.getColumnsForOrientation(it) },
                        getItemsForCategory = { state.getItemsForCategory(it) },
                    )
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = state.activeCategory,
                )
            }
            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryScreenModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        screenModel.clearSelection()
                    },
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

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
