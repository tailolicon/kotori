package eu.kanade.tachiyomi.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.browse.anime.AnimeExtensionScreen
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.AnimeExtensionReposScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriInlineSearchField
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.extension.details.AnimeExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.AnimeSourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchItemResult
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSourceFilter
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.UpcomingScreen
import mihon.feature.upcoming.anime.UpcomingAnimeScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.presentation.browse.GlobalSearchContent as MangaGlobalSearchContent
import eu.kanade.presentation.browse.anime.GlobalSearchContent as AnimeGlobalSearchContent

/**
 * Tablet Browse deliberately owns only presentation and navigation state. Source and extension
 * data continue to come from their canonical screen models, so this dashboard cannot hide sources
 * or invent a second update state.
 */
@Composable
fun Screen.KotoriTabletMangaBrowse(
    tabs: List<TabContent>,
    state: PagerState,
) {
    val navigator = LocalNavigator.currentOrThrow
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    var activeTab by remember { mutableIntStateOf(state.currentPage) }
    val tabScope = rememberCoroutineScope()
    LaunchedEffect(state.currentPage) { activeTab = state.currentPage }
    val extensionModel = rememberScreenModel { ExtensionsScreenModel() }
    val searchModel = rememberScreenModel { GlobalSearchScreenModel() }
    val searchState by searchModel.state.collectAsState()
    val extensionState by extensionModel.state.collectAsState()
    var libraryOnly by rememberSaveable { mutableStateOf(false) }
    val mode = remember { Injekt.get<UiPreferences>().activeMediaMode.get() }

    KotoriScreenScaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        TabletBrowseHost(
            tabs = tabs,
            activeTab = activeTab,
            onSelectTab = { index ->
                activeTab = index
                tabScope.launch { state.scrollToPage(index) }
            },
            // Read back from whichever model the typing goes to. Reading the global search state
            // unconditionally while routing Tiện ích keystrokes into the extension model left the
            // box permanently empty on that tab: the field is controlled, so it renders the value
            // it is given, and it was being given a state nothing was writing to. Typing looked
            // like it did nothing at all.
            searchQuery = if (activeTab == EXTENSIONS_TAB) {
                extensionState.searchQuery.orEmpty()
            } else {
                searchState.searchQuery.orEmpty()
            },
            activeSearchFilters = buildSet {
                add(if (searchState.sourceFilter == SourceFilter.All) 0 else 1)
                if (libraryOnly) add(2)
                if (searchState.onlyShowHasResults) add(3)
            },
            searchPlaceholder = if (activeTab == EXTENSIONS_TAB) {
                "Tìm tiện ích…"
            } else {
                "Tìm trên mọi nguồn…"
            },
            onSearchQueryChange = { query ->
                // The box sits above whichever tab is open, so it has to ask that tab's question.
                // Typing on Tiện ích used to run a global title search and leave the extension
                // list untouched.
                if (activeTab == EXTENSIONS_TAB) {
                    extensionModel.search(query)
                } else {
                    searchModel.updateSearchQuery(query)
                }
            },
            onSubmitSearch = { if (activeTab != EXTENSIONS_TAB) searchModel.search() },
            onSelectSearchFilter = { index ->
                when (index) {
                    0 -> searchModel.setSourceFilter(SourceFilter.All)
                    1 -> searchModel.setSourceFilter(SourceFilter.PinnedOnly)
                    2 -> libraryOnly = !libraryOnly
                    3 -> searchModel.toggleFilterResults()
                }
            },
            onResetSearch = {
                searchModel.updateSearchQuery("")
                extensionModel.search(null)
                libraryOnly = false
            },
            onOpenCalendar = { navigator.push(UpcomingScreen()) },
            onOpenSourceFilter = { navigator.push(SourcesFilterScreen()) },
            onOpenExtensionFilter = { navigator.push(ExtensionFilterScreen()) },
            onOpenExtensionStores = { navigator.push(ExtensionStoresScreen()) },
            onOpenMigrateHelp = {
                uriHandler.openUri("https://mihon.app/docs/guides/source-migration")
            },
            sourceContent = {
                tabs.first().content(PaddingValues(), snackbarHostState)
            },
            pendingUpdates = {
                if (mode == MediaType.NOVEL) {
                    NoPendingUpdates()
                } else {
                    MangaPendingUpdates(
                        screenModel = extensionModel,
                        onOpenExtension = { navigator.push(ExtensionDetailsScreen(it)) },
                    )
                }
            },
            searchResults = {
                val items = searchState.items.filterLibraryManga(libraryOnly)
                MangaGlobalSearchContent(
                    items = items,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    getManga = { searchModel.getManga(it) },
                    onClickSource = {
                        navigator.push(BrowseSourceScreen(it.id, searchState.searchQuery))
                    },
                    onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                    onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
                )
            },
            tabContent = {
                tabs[activeTab.coerceIn(tabs.indices)].content(PaddingValues(), snackbarHostState)
            },
        )
    }
}

/** Anime uses its own extension and calendar stacks but shares the exact tablet composition. */
@Composable
fun Screen.KotoriTabletAnimeBrowse(
    tabs: List<TabContent>,
    state: PagerState,
) {
    val navigator = LocalNavigator.currentOrThrow
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    var activeTab by remember { mutableIntStateOf(state.currentPage) }
    val tabScope = rememberCoroutineScope()
    LaunchedEffect(state.currentPage) { activeTab = state.currentPage }
    val extensionModel = rememberScreenModel { AnimeExtensionsScreenModel() }
    val searchModel = rememberScreenModel { GlobalAnimeSearchScreenModel() }
    val searchState by searchModel.state.collectAsState()
    val extensionState by extensionModel.state.collectAsState()
    var libraryOnly by rememberSaveable { mutableStateOf(false) }

    KotoriScreenScaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        TabletBrowseHost(
            tabs = tabs,
            activeTab = activeTab,
            onSelectTab = { index ->
                activeTab = index
                tabScope.launch { state.scrollToPage(index) }
            },
            // See the note in KotoriTabletBrowse — same field, same fault.
            searchQuery = if (activeTab == EXTENSIONS_TAB) {
                extensionState.searchQuery.orEmpty()
            } else {
                searchState.searchQuery.orEmpty()
            },
            activeSearchFilters = buildSet {
                add(if (searchState.sourceFilter == AnimeSourceFilter.All) 0 else 1)
                if (libraryOnly) add(2)
                if (searchState.onlyShowHasResults) add(3)
            },
            searchPlaceholder = if (activeTab == EXTENSIONS_TAB) {
                "Tìm tiện ích…"
            } else {
                "Tìm trên mọi nguồn…"
            },
            onSearchQueryChange = { query ->
                // The box sits above whichever tab is open, so it has to ask that tab's question.
                // Typing on Tiện ích used to run a global title search and leave the extension
                // list untouched.
                if (activeTab == EXTENSIONS_TAB) {
                    extensionModel.search(query)
                } else {
                    searchModel.updateSearchQuery(query)
                }
            },
            onSubmitSearch = { if (activeTab != EXTENSIONS_TAB) searchModel.search() },
            onSelectSearchFilter = { index ->
                when (index) {
                    0 -> searchModel.setSourceFilter(AnimeSourceFilter.All)
                    1 -> searchModel.setSourceFilter(AnimeSourceFilter.PinnedOnly)
                    2 -> libraryOnly = !libraryOnly
                    3 -> searchModel.toggleFilterResults()
                }
            },
            onResetSearch = {
                searchModel.updateSearchQuery("")
                extensionModel.search(null)
                libraryOnly = false
            },
            onOpenCalendar = { navigator.push(UpcomingAnimeScreen()) },
            onOpenSourceFilter = { navigator.push(AnimeSourcesFilterScreen()) },
            onOpenExtensionFilter = { navigator.push(AnimeExtensionFilterScreen()) },
            onOpenExtensionStores = { navigator.push(AnimeExtensionReposScreen()) },
            onOpenMigrateHelp = {
                uriHandler.openUri("https://aniyomi.org/help/guides/source-migration/")
            },
            sourceContent = {
                tabs.first().content(PaddingValues(), snackbarHostState)
            },
            pendingUpdates = {
                AnimePendingUpdates(
                    screenModel = extensionModel,
                    onOpenExtension = { navigator.push(AnimeExtensionDetailsScreen(it)) },
                )
            },
            searchResults = {
                val items = searchState.items.filterLibraryAnime(libraryOnly)
                AnimeGlobalSearchContent(
                    items = items,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    getAnime = { searchModel.getAnime(it) },
                    onClickSource = {
                        navigator.push(BrowseAnimeSourceScreen(it.id, searchState.searchQuery))
                    },
                    onClickItem = { navigator.push(AnimeScreen(it.id, true)) },
                    onLongClickItem = { navigator.push(AnimeScreen(it.id, true)) },
                )
            },
            tabContent = {
                tabs[activeTab.coerceIn(tabs.indices)].content(PaddingValues(), snackbarHostState)
            },
        )
    }
}

@Composable
private fun TabletBrowseHost(
    tabs: List<TabContent>,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    searchQuery: String,
    searchPlaceholder: String,
    activeSearchFilters: Set<Int>,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSelectSearchFilter: (Int) -> Unit,
    onResetSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSourceFilter: () -> Unit,
    onOpenExtensionFilter: () -> Unit,
    onOpenExtensionStores: (() -> Unit)? = null,
    onOpenMigrateHelp: (() -> Unit)? = null,
    sourceContent: @Composable () -> Unit,
    pendingUpdates: @Composable () -> Unit,
    searchResults: @Composable () -> Unit,
    tabContent: @Composable () -> Unit,
) {
    // Deliberately not saveable. Browsing a source and coming back restored the expanded search
    // with nothing in the box, so the dashboard came back wearing its search clothes — filter
    // pills instead of Bộ lọc and Lịch mùa — over a pane with nothing to show.
    var searchActive by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        BrowseTab.markTabletHostVisible(true)
        onDispose { BrowseTab.markTabletHostVisible(false) }
    }
    LaunchedEffect(Unit) {
        BrowseTab.focusInlineSearchFlow().collectLatest {
            searchActive = true
        }
    }
    val closeSearch = {
        searchActive = false
        focusManager.clearFocus(force = true)
        onResetSearch()
    }
    BackHandler(enabled = searchActive, onBack = closeSearch)

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(KotoriTabletTokens.browseSourcePane)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.strings.browse),
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = KotoriColors.textPrimary,
            )
            TabletBrowseSegments(
                tabs = tabs,
                activeTab = activeTab,
                onSelectTab = { index ->
                    if (searchActive) closeSearch()
                    onSelectTab(index)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.weight(1f)) {
                sourceContent()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Color(0x12FFFFFF)),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KotoriInlineSearchField(
                    value = searchQuery,
                    onValueChange = {
                        searchActive = true
                        onSearchQueryChange(it)
                    },
                    placeholder = searchPlaceholder,
                    leadingIcon = Icons.Outlined.TravelExplore,
                    gradientBorder = true,
                    onClear = closeSearch,
                    onSearch = onSubmitSearch,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (it.hasFocus) searchActive = true }
                        .focusGroup(),
                )
                // Mọi nguồn / Đã ghim / Có trong thư viện / Có kết quả narrow a title search across
                // sources. On Tiện ích the box searches extension names, so those four answered a
                // question nobody had asked and hid Bộ lọc and Cửa hàng behind an unrelated set.
                if (searchActive && activeTab != EXTENSIONS_TAB) {
                    TabletSearchFilters(
                        activeFilters = activeSearchFilters,
                        onSelect = onSelectSearchFilter,
                    )
                } else {
                    if (activeTab == 0 || activeTab == 1) {
                        TabletBrowseAction(
                            label = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            onClick = if (activeTab == 0) onOpenSourceFilter else onOpenExtensionFilter,
                        )
                    }
                    if (activeTab == EXTENSIONS_TAB && onOpenExtensionStores != null) {
                        TabletBrowseAction(
                            label = stringResource(MR.strings.extensionStores),
                            icon = Icons.Outlined.TravelExplore,
                            onClick = onOpenExtensionStores,
                        )
                    }
                    if (activeTab == 2 && onOpenMigrateHelp != null) {
                        TabletBrowseAction(
                            label = stringResource(MR.strings.migration_help_guide),
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            onClick = onOpenMigrateHelp,
                        )
                    }
                    TabletBrowseAction(
                        label = "Lịch mùa",
                        icon = Icons.Outlined.CalendarMonth,
                        onClick = onOpenCalendar,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                when {
                    // Tiện ích filters itself from the same box, so the pane keeps showing that
                    // list. Handing it the global title results instead left an empty pane while
                    // the thing being searched sat filtered out of view.
                    activeTab == EXTENSIONS_TAB -> tabContent()
                    // An empty query has no results to draw, so `searchActive` alone emptied the
                    // pane — and it turns on by itself whenever the field takes focus back, which
                    // is what happens on returning from a source. Coming back from browsing a
                    // source blanked the whole right-hand side until something was typed.
                    searchActive && searchQuery.isNotBlank() -> searchResults()
                    activeTab == 0 -> pendingUpdates()
                    else -> tabContent()
                }
            }
        }
    }
}

@Composable
private fun TabletBrowseSegments(
    tabs: List<TabContent>,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    Row(
        modifier = modifier
            .clip(KotoriTabletShapes.inlineSearch)
            .background(Color(0x0FFFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), KotoriTabletShapes.inlineSearch)
            .padding(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == activeTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(if (selected) KotoriTabletShapes.segmentActiveSmall else RoundedCornerShape(15.dp))
                    .then(if (selected) Modifier.background(accent.gradient) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelectTab(index) }
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(tab.titleRes),
                        fontFamily = BeVietnamProFamily,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = if (selected) accent.onAccent else KotoriColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    tab.badgeNumber?.takeIf { it > 0 }?.let { badge ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Color(0x33FFFFFF) else KotoriColors.glassBgPressed),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = badge.toString(),
                                fontFamily = BeVietnamProFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletSearchFilters(
    activeFilters: Set<Int>,
    onSelect: (Int) -> Unit,
) {
    val accent = KotoriTheme.accent
    val labels = listOf("Mọi nguồn", "Đã ghim", "Có trong thư viện", stringResource(MR.strings.has_results))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val selected = index in activeFilters
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .then(
                        if (selected) {
                            Modifier.background(accent.gradient)
                        } else {
                            Modifier
                                .background(Color(0x0FFFFFFF))
                                .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(15.dp))
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TabletBrowseAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val accent = KotoriTheme.accent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x12FFFFFF))
            .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accent.light,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = KotoriColors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MangaPendingUpdates(
    screenModel: ExtensionsScreenModel,
    onOpenExtension: (String) -> Unit,
) {
    val state by screenModel.state.collectAsState()
    val pendingState = state.copy(
        items = state.items.filterKeys { header ->
            header is ExtensionUiModel.Header.Resource && header.textRes == MR.strings.ext_updates_pending
        },
    )
    if (!pendingState.isLoading && pendingState.isEmpty) {
        NoPendingUpdates()
        return
    }

    ExtensionScreen(
        state = pendingState,
        contentPadding = PaddingValues(),
        searchQuery = null,
        onLongClickItem = {},
        onClickItemCancel = screenModel::cancelInstallUpdateExtension,
        onOpenWebView = {},
        onInstallExtension = screenModel::installExtension,
        onUninstallExtension = screenModel::uninstallExtension,
        onUpdateExtension = screenModel::updateExtension,
        onTrustExtension = screenModel::trustExtension,
        onOpenExtension = { onOpenExtension(it.pkgName) },
        onClickUpdateAll = screenModel::updateAllExtensions,
        onRefresh = screenModel::findAvailableExtensions,
    )
}

@Composable
private fun AnimePendingUpdates(
    screenModel: AnimeExtensionsScreenModel,
    onOpenExtension: (String) -> Unit,
) {
    val state by screenModel.state.collectAsState()
    val pendingState = state.copy(
        items = state.items.filterKeys { header ->
            header is AnimeExtensionUiModel.Header.Resource && header.textRes == MR.strings.ext_updates_pending
        }.toMutableMap(),
    )
    if (!pendingState.isLoading && pendingState.isEmpty) {
        NoPendingUpdates()
        return
    }

    AnimeExtensionScreen(
        state = pendingState,
        contentPadding = PaddingValues(),
        searchQuery = null,
        onLongClickItem = {},
        onClickItemCancel = screenModel::cancelInstallUpdateExtension,
        onOpenWebView = {},
        onInstallExtension = screenModel::installExtension,
        onUninstallExtension = screenModel::uninstallExtension,
        onUpdateExtension = screenModel::updateExtension,
        onTrustExtension = screenModel::trustExtension,
        onOpenExtension = { onOpenExtension(it.pkgName) },
        onClickUpdateAll = screenModel::updateAllExtensions,
        onRefresh = screenModel::findAvailableExtensions,
    )
}

@Composable
private fun NoPendingUpdates() {
    EmptyScreen(
        stringRes = MR.strings.update_check_no_new_updates,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun <K> Map<K, SearchItemResult>.filterLibraryManga(
    libraryOnly: Boolean,
): Map<K, SearchItemResult> {
    if (!libraryOnly) return this
    return mapNotNull { (source, result) ->
        when (result) {
            is SearchItemResult.Success ->
                result.result
                    .filter { it.favorite }
                    .takeIf { it.isNotEmpty() }
                    ?.let { source to result.copy(result = it) }
            else -> source to result
        }
    }.toMap()
}

private fun <K> Map<K, AnimeSearchItemResult>.filterLibraryAnime(
    libraryOnly: Boolean,
): Map<K, AnimeSearchItemResult> {
    if (!libraryOnly) return this
    return mapNotNull { (source, result) ->
        when (result) {
            is AnimeSearchItemResult.Success ->
                result.result
                    .filter { it.favorite }
                    .takeIf { it.isNotEmpty() }
                    ?.let { source to result.copy(result = it) }
            else -> source to result
        }
    }.toMap()
}

/** Index of the Tiện ích tab. Sources, Extensions, Migrate — the order Browse has always used. */
private const val EXTENSIONS_TAB = 1
