package eu.kanade.tachiyomi.ui.browse.anime.source.browse

import eu.kanade.presentation.theme.kotori.KotoriColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import eu.kanade.presentation.browse.components.rememberBrowseHeaderScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.browse.RemoveEntryDialog
import eu.kanade.presentation.browse.anime.BrowseAnimeSourceContent
import eu.kanade.presentation.browse.anime.MissingSourceScreen
import eu.kanade.presentation.browse.KotoriFeedItem
import eu.kanade.presentation.browse.KotoriFeedShelf
import eu.kanade.presentation.browse.KotoriSourceFeed
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import eu.kanade.presentation.browse.KotoriGenreRow
import eu.kanade.presentation.browse.KotoriFeedPill
import eu.kanade.presentation.browse.KotoriFilterFab
import eu.kanade.presentation.browse.anime.components.BrowseAnimeSourceToolbar
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.entries.anime.DuplicateAnimeDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.ui.browse.anime.extension.details.AnimeSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.anime.migration.anime.season.MigrateSeasonSelectScreen
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeDialog
import eu.kanade.tachiyomi.ui.browse.anime.migration.search.MigrateAnimeDialogScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.anime.AnimeCategoryScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.entries.anime.LocalAnimeSource

data class BrowseAnimeSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { BrowseAnimeSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(
                    null,
                )
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubAnimeSource) {
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

        val onHelpClick = { uriHandler.openUri(LocalAnimeSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? AnimeHttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.baseUrl,
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? AnimeHttpSource)?.baseUrl
        }

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

        var topBarHeight by remember { mutableIntStateOf(0) }
        val headerScroll = rememberBrowseHeaderScrollState()
        Scaffold(
            modifier = Modifier.nestedScroll(headerScroll.connection),
            // Transparent so the window's aurora shows through, the same as the manga screen.
            containerColor = Color.Transparent,
            topBar = {
                Column(
                    modifier = Modifier
                        .then(headerScroll.headerModifier())
                        // Frosted rather than solid, so the aurora keeps showing through while
                        // covers passing underneath stay legible.
                        .background(KotoriColors.bgBase.copy(alpha = 0.82f))
                        .onSizeChanged { topBarHeight = it.height },
                ) {
                    BrowseAnimeSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(AnimeSourcePreferencesScreen(sourceId)) },
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
                        if ((screenModel.source as AnimeCatalogueSource).supportsLatest) {
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

                    HorizontalDivider()
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
            // The feed is the browsing state; a query or a filter turns the screen into the grid
            // of results instead. The two are exclusive in the design, not stacked.
            val showFeed = state.listing !is Listing.Search && !state.showGrid && feed.loaded
            if (showFeed) {
                val onOpen: (Anime) -> Unit = { navigator.push(AnimeScreen(it.id, true)) }
                val onToggleFavorite: (Anime) -> Unit = { anime ->
                    scope.launchIO {
                        if (anime.favorite) {
                            screenModel.setDialog(BrowseAnimeSourceScreenModel.Dialog.RemoveAnime(anime))
                        } else {
                            screenModel.addFavorite(anime)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                val toItem: (Anime) -> KotoriFeedItem = { anime ->
                    KotoriFeedItem(
                        key = anime.id.toString(),
                        title = anime.title,
                        cover = anime.asAnimeCover(),
                        statusLabel = animeStatusLabel(anime.status),
                        inLibrary = anime.favorite,
                        onClick = { onOpen(anime) },
                        onLongClick = { onToggleFavorite(anime) },
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
                                // A status shelf is something this screen worked out, not a feed
                                // the source can serve, so there is nothing fuller to open.
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
            BrowseAnimeSourceContent(
                source = screenModel.source,
                animeList = screenModel.animePagerFlowFlow.collectAsLazyPagingItems(),
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation),
                topBarHeight = topBarHeight,
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalAnimeSourceHelpClick = onHelpClick,
                onAnimeClick = { navigator.push((AnimeScreen(it.id, true))) },
                onAnimeLongClick = { anime ->
                    scope.launchIO {
                        val duplicateAnime = screenModel.getDuplicateAnimelibAnime(anime)
                        when {
                            anime.favorite -> screenModel.setDialog(
                                BrowseAnimeSourceScreenModel.Dialog.RemoveAnime(anime),
                            )
                            duplicateAnime != null -> screenModel.setDialog(
                                BrowseAnimeSourceScreenModel.Dialog.AddDuplicateAnime(
                                    anime,
                                    duplicateAnime,
                                ),
                            )
                            else -> screenModel.addFavorite(anime)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseAnimeSourceScreenModel.Dialog.Filter -> {
                SourceFilterAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.AddDuplicateAnime -> {
                DuplicateAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.anime) },
                    onOpenAnime = { navigator.push(AnimeScreen(dialog.duplicate.id)) },
                    onMigrate = {
                        screenModel.setDialog(
                            BrowseAnimeSourceScreenModel.Dialog.Migrate(dialog.anime, dialog.duplicate),
                        )
                    },
                )
            }

            is BrowseAnimeSourceScreenModel.Dialog.Migrate -> {
                MigrateAnimeDialog(
                    oldAnime = dialog.oldAnime,
                    newAnime = dialog.newAnime,
                    screenModel = MigrateAnimeDialogScreenModel(),
                    onDismissRequest = onDismissRequest,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.oldAnime.id)) },
                    onClickSeasons = { navigator.push(MigrateSeasonSelectScreen(dialog.oldAnime, dialog.newAnime)) },
                    onPopScreen = {
                        onDismissRequest()
                    },
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.RemoveAnime -> {
                RemoveEntryDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeAnimeFavorite(dialog.anime)
                    },
                    entryToRemove = dialog.anime.title,
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.ChangeAnimeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(AnimeCategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeAnimeFavorite(dialog.anime)
                        screenModel.moveAnimeToCategories(dialog.anime, include)
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
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

/** The Vietnamese label a source's status maps to, or `null` when it says nothing useful. */
private fun animeStatusLabel(status: Long): String? = when (status.toInt()) {
    SAnime.ONGOING -> "Đang chiếu"
    SAnime.COMPLETED -> "Hoàn thành"
    SAnime.ON_HIATUS -> "Tạm ngưng"
    SAnime.CANCELLED -> "Đã huỷ"
    SAnime.PUBLISHING_FINISHED -> "Đã chiếu xong"
    else -> null
}
