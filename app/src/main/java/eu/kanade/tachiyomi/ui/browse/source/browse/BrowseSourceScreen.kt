package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import eu.kanade.presentation.browse.components.rememberBrowseHeaderScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.KotoriFeedItem
import eu.kanade.presentation.browse.KotoriFeedShelf
import eu.kanade.presentation.browse.KotoriSourceFeed
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import eu.kanade.presentation.browse.KotoriGenreRow
import eu.kanade.presentation.browse.KotoriFeedPill
import eu.kanade.presentation.browse.KotoriFilterFab
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.theme.kotori.AuroraBackground
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriChip
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()
        val feed by screenModel.feed.collectAsState()

        // Shared by the header row and the feed, so both agree on what is selected.
        val onSelectGenre: (String?) -> Unit = { genre ->
            if (genre == null) {
                screenModel.resetFilters()
                screenModel.setListing(Listing.Popular)
            } else {
                screenModel.searchGenre(genre)
            }
        }
        LaunchedEffect(screenModel.source) { screenModel.loadFeed() }

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.getHomeUrl(),
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.getHomeUrl()
        }

        val headerScroll = rememberBrowseHeaderScrollState()

        AuroraBackground {
            Scaffold(
                modifier = Modifier.nestedScroll(headerScroll.connection),
                containerColor = Color.Transparent,
                topBar = {
                    Column(
                        modifier = Modifier
                            .then(headerScroll.headerModifier())
                            // Frosted rather than solid: the aurora keeps showing through, which is
                            // the point of the theme, while covers passing underneath stay legible.
                            .background(KotoriColors.bgBase.copy(alpha = 0.82f))
                            .pointerInput(Unit) {},
                    ) {
                        BrowseSourceToolbar(
                            searchQuery = state.toolbarQuery,
                            onSearchQueryChange = screenModel::setToolbarQuery,
                            source = screenModel.source,
                            displayMode = screenModel.displayMode,
                            onDisplayModeChange = { screenModel.displayMode = it },
                            navigateUp = navigateUp,
                            onWebViewClick = onWebViewClick,
                            onHelpClick = onHelpClick,
                            onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                            onSearch = screenModel::search,
                        )

                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KotoriFeedPill(
                                label = "Phổ biến",
                                selected = state.listing == Listing.Popular,
                                latest = false,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Popular)
                                },
                            )
                            if (screenModel.source.supportsLatest) {
                                KotoriFeedPill(
                                    label = "Mới nhất",
                                    selected = state.listing == Listing.Latest,
                                    latest = true,
                                    onClick = {
                                        screenModel.resetFilters()
                                        screenModel.setListing(Listing.Latest)
                                    },
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                floatingActionButton = {
                    if (state.filters.isNotEmpty()) {
                        KotoriFilterFab(
                            label = "Bộ lọc",
                            onClick = screenModel::openFilterSheet,
                        )
                    }
                },
            ) { paddingValues ->
                // The feed is the browsing state; a query or a filter turns the screen into the
                // grid of results instead. The two are exclusive in the design.
                val showFeed = state.listing !is Listing.Search && !state.showGrid && feed.loaded
                if (showFeed) {
                    val onOpen: (Manga) -> Unit = { navigator.push(MangaScreen(it.id, true)) }
                    val toItem: (Manga) -> KotoriFeedItem = { manga ->
                        KotoriFeedItem(
                            key = manga.id.toString(),
                            title = manga.title,
                            cover = manga.asMangaCover(),
                            statusLabel = mangaStatusLabel(manga.status),
                            inLibrary = manga.favorite,
                            onClick = { onOpen(manga) },
                            onLongClick = {
                                scope.launchIO {
                                    if (manga.favorite) {
                                        screenModel.setDialog(
                                            BrowseSourceScreenModel.Dialog.RemoveManga(manga),
                                        )
                                    } else {
                                        screenModel.addFavorite(manga)
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                        )
                    }
                    KotoriSourceFeed(
                        hero = feed.hero?.let(toItem),
                        top = feed.top.map(toItem),
                        shelves = feed.shelves.map { shelf ->
                            KotoriFeedShelf(
                                label = shelf.label,
                                sub = shelf.sub,
                                items = shelf.items.map(toItem),
                                onSeeAll = when {
                                    shelf.genre != null -> ({ screenModel.searchGenre(shelf.genre) })
                                    shelf.listing != null -> ({ screenModel.showAll(shelf.listing) })
                                    // A status shelf is something this screen worked out, not a
                                    // feed the source can serve, so nothing fuller can be opened.
                                    else -> null
                                },
                            )
                        },
                        genres = feed.genres,
                        activeGenre = state.activeGenre,
                        onSelectGenre = onSelectGenre,
                        onPlayHero = { feed.hero?.let(onOpen) },
                        onSeeAllTop = { screenModel.showAll(Listing.Popular) },
                        contentPadding = paddingValues,
                    )
                    return@Scaffold
                }
                BrowseSourceContent(
                    source = screenModel.source,
                    mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                    columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                    displayMode = screenModel.displayMode,
                    snackbarHostState = snackbarHostState,
                    contentPadding = paddingValues,
                    onWebViewClick = onWebViewClick,
                    onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                    onLocalSourceHelpClick = onHelpClick,
                    onMangaClick = { navigator.push((MangaScreen(it.id, true))) },
                    onMangaLongClick = { manga ->
                        scope.launchIO {
                            val duplicates = screenModel.getDuplicateLibraryManga(manga)
                            when {
                                manga.favorite -> screenModel.setDialog(
                                    BrowseSourceScreenModel.Dialog.RemoveManga(manga),
                                )
                                duplicates.isNotEmpty() -> screenModel.setDialog(
                                    BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                                )
                                else -> screenModel.addFavorite(manga)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                )
            }
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        // A genre tap can replace the detail screen before this screen starts collecting. Buffer
        // the navigation event so cancellation of the old screen cannot silently drop the filter.
        private val queryEvent = Channel<SearchType>(Channel.BUFFERED)
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

/** The Vietnamese label a source's status maps to, or `null` when it says nothing useful. */
private fun mangaStatusLabel(status: Long): String? = when (status.toInt()) {
    SManga.ONGOING -> "Đang ra"
    SManga.COMPLETED -> "Hoàn thành"
    SManga.ON_HIATUS -> "Tạm ngưng"
    SManga.CANCELLED -> "Đã huỷ"
    SManga.PUBLISHING_FINISHED -> "Đã ra xong"
    else -> null
}
