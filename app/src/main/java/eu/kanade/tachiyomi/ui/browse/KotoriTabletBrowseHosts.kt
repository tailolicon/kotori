package eu.kanade.tachiyomi.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.KotoriSearchShelf
import eu.kanade.presentation.browse.KotoriShelfCover
import eu.kanade.presentation.browse.KotoriShelfMessage
import eu.kanade.presentation.browse.KotoriShelfShimmer
import eu.kanade.presentation.browse.KotoriTabletBrowseLayout
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSourceFilter
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchItemResult
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
                val mode = remember { Injekt.get<UiPreferences>().activeMediaMode.get() }
                val typeLabel = if (mode == MediaType.NOVEL) "NOVEL" else "MANGA"
                LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    searchState.filteredItems.forEach { (source, result) ->
                        item(key = source.id) {
                            KotoriSearchShelf(
                                sourceName = source.name,
                                typeTag = "$typeLabel · ${source.lang.uppercase()}",
                                countLabel = (result as? SearchItemResult.Success)
                                    ?.result
                                    ?.size
                                    ?.let { "$it kết quả" },
                                onClickSource = {
                                    navigator.push(BrowseSourceScreen(source.id, searchState.searchQuery))
                                },
                                modifier = Modifier.animateItem(),
                            ) {
                                when (result) {
                                    SearchItemResult.Loading -> KotoriShelfShimmer()
                                    is SearchItemResult.Error -> KotoriShelfMessage(
                                        text = result.throwable.message
                                            ?: stringResource(MR.strings.unknown_error),
                                    )
                                    is SearchItemResult.Success -> if (result.isEmpty) {
                                        KotoriShelfMessage(
                                            text = "${source.name} — không có kết quả",
                                        )
                                    } else {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            itemsIndexed(result.result) { index, manga ->
                                                val title by searchModel.getManga(manga)
                                                KotoriShelfCover(
                                                    title = title.title,
                                                    coverData = title.asMangaCover(),
                                                    inLibrary = title.favorite,
                                                    index = index,
                                                    onClick = { navigator.push(MangaScreen(title.id, true)) },
                                                    onLongClick = { navigator.push(MangaScreen(title.id, true)) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    searchState.filteredItems.forEach { (source, result) ->
                        item(key = source.id) {
                            KotoriSearchShelf(
                                sourceName = source.name,
                                typeTag = "ANIME · ${source.lang.uppercase()}",
                                countLabel = (result as? AnimeSearchItemResult.Success)
                                    ?.result
                                    ?.size
                                    ?.let { "$it kết quả" },
                                onClickSource = {
                                    navigator.push(BrowseAnimeSourceScreen(source.id, searchState.searchQuery))
                                },
                                modifier = Modifier.animateItem(),
                            ) {
                                when (result) {
                                    AnimeSearchItemResult.Loading -> KotoriShelfShimmer()
                                    is AnimeSearchItemResult.Error -> KotoriShelfMessage(
                                        text = result.throwable.message
                                            ?: stringResource(MR.strings.unknown_error),
                                    )
                                    is AnimeSearchItemResult.Success -> if (result.isEmpty) {
                                        KotoriShelfMessage(
                                            text = "${source.name} — không có kết quả",
                                        )
                                    } else {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            itemsIndexed(result.result) { index, anime ->
                                                val title by searchModel.getAnime(anime)
                                                KotoriShelfCover(
                                                    title = title.title,
                                                    coverData = title.asAnimeCover(),
                                                    inLibrary = title.favorite,
                                                    index = index,
                                                    onClick = { navigator.push(AnimeScreen(title.id, true)) },
                                                    onLongClick = { navigator.push(AnimeScreen(title.id, true)) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier,
        )
    }
}
