package eu.kanade.tachiyomi.ui.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.GlobalSearchContent
import eu.kanade.presentation.browse.KotoriTabletBrowseLayout
import eu.kanade.presentation.browse.anime.GlobalSearchContent as GlobalAnimeSearchContent
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSourceFilter
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val FILTER_LABELS = listOf("Mọi nguồn", "Đã ghim", "Có trong thư viện")

/**
 * T6 for the manga and novel modes: the source column on the left, a live global
 * search in the results pane so there is no navigation push to search.
 */
@Composable
fun Screen.KotoriTabletMangaBrowse(
    tabs: List<TabContent>,
    state: PagerState,
) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchModel = rememberScreenModel { GlobalSearchScreenModel() }
    val searchState by searchModel.state.collectAsState()

    KotoriScreenScaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        KotoriTabletBrowseLayout(
            title = stringResource(MR.strings.browse),
            tabLabels = tabs.map { stringResource(it.titleRes) },
            tabBadges = tabs.map { it.badgeNumber },
            activeTab = state.currentPage,
            onSelectTab = { scope.launch { state.animateScrollToPage(it) } },
            searchQuery = searchState.searchQuery.orEmpty(),
            onSearchChange = searchModel::updateSearchQuery,
            onSearch = { searchModel.search() },
            onClearSearch = { searchModel.updateSearchQuery("") },
            filterLabels = FILTER_LABELS,
            activeFilter = when {
                searchState.onlyShowHasResults -> 2
                searchState.sourceFilter == SourceFilter.PinnedOnly -> 1
                else -> 0
            },
            onSelectFilter = { index ->
                when (index) {
                    0 -> {
                        searchModel.setSourceFilter(SourceFilter.All)
                        if (searchState.onlyShowHasResults) searchModel.toggleFilterResults()
                    }
                    1 -> {
                        searchModel.setSourceFilter(SourceFilter.PinnedOnly)
                        if (searchState.onlyShowHasResults) searchModel.toggleFilterResults()
                    }
                    else -> if (!searchState.onlyShowHasResults) searchModel.toggleFilterResults()
                }
            },
            sourceColumn = {
                tabs[state.currentPage].content(PaddingValues(), snackbarHostState)
            },
            results = {
                GlobalSearchContent(
                    items = searchState.filteredItems,
                    contentPadding = PaddingValues(),
                    getManga = { searchModel.getManga(it) },
                    onClickSource = { navigator.push(BrowseSourceScreen(it.id, searchState.searchQuery)) },
                    onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                    onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
                )
            },
            modifier = Modifier,
        )
    }
}

/** T6 for the anime mode. */
@Composable
fun Screen.KotoriTabletAnimeBrowse(
    tabs: List<TabContent>,
    state: PagerState,
) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchModel = rememberScreenModel { GlobalAnimeSearchScreenModel() }
    val searchState by searchModel.state.collectAsState()

    KotoriScreenScaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        KotoriTabletBrowseLayout(
            title = stringResource(MR.strings.browse),
            tabLabels = tabs.map { stringResource(it.titleRes) },
            tabBadges = tabs.map { it.badgeNumber },
            activeTab = state.currentPage,
            onSelectTab = { scope.launch { state.animateScrollToPage(it) } },
            searchQuery = searchState.searchQuery.orEmpty(),
            onSearchChange = searchModel::updateSearchQuery,
            onSearch = { searchModel.search() },
            onClearSearch = { searchModel.updateSearchQuery("") },
            filterLabels = FILTER_LABELS,
            activeFilter = when {
                searchState.onlyShowHasResults -> 2
                searchState.sourceFilter == AnimeSourceFilter.PinnedOnly -> 1
                else -> 0
            },
            onSelectFilter = { index ->
                when (index) {
                    0 -> {
                        searchModel.setSourceFilter(AnimeSourceFilter.All)
                        if (searchState.onlyShowHasResults) searchModel.toggleFilterResults()
                    }
                    1 -> {
                        searchModel.setSourceFilter(AnimeSourceFilter.PinnedOnly)
                        if (searchState.onlyShowHasResults) searchModel.toggleFilterResults()
                    }
                    else -> if (!searchState.onlyShowHasResults) searchModel.toggleFilterResults()
                }
            },
            sourceColumn = {
                tabs[state.currentPage].content(PaddingValues(), snackbarHostState)
            },
            results = {
                GlobalAnimeSearchContent(
                    items = searchState.filteredItems,
                    contentPadding = PaddingValues(),
                    getAnime = { searchModel.getAnime(it) },
                    onClickSource = { navigator.push(BrowseAnimeSourceScreen(it.id, searchState.searchQuery)) },
                    onClickItem = { navigator.push(AnimeScreen(it.id, true)) },
                    onLongClickItem = { navigator.push(AnimeScreen(it.id, true)) },
                )
            },
            modifier = Modifier,
        )
    }
}
