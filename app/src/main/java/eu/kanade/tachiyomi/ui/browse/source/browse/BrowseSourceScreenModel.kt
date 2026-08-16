package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.CatalogueSource
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import mihon.domain.manga.model.toDomainManga
import eu.kanade.tachiyomi.ui.browse.GenreFilterMatcher
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.KOTORI_COMMON_GENRES
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.PREFERRED_SHELF_GENRES
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.GENRE_SHELF_COUNT
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    getIncognitoState: GetIncognitoState = Injekt.get(),
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        mutableState.update {
            var query: String? = null
            var listing = it.listing

            if (listing is Listing.Search) {
                query = listing.query
                listing = Listing.Search(query, source.getFilterList())
            }

            it.copy(
                listing = listing,
                filters = source.getFilterList(),
                toolbarQuery = query,
            )
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val mangaPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteManga(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { manga ->
                    getManga.subscribe(manga.url, manga.source)
                        .map { it ?: manga }
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(ioCoroutineScope)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    // ============================== Feed (screen 18) ==============================

    /**
     * The shelf feed a source opens on: a hero, genre chips, a ranked row and status shelves.
     *
     * Built from the two feeds a source actually has — popular and latest — rather than from
     * per-shelf endpoints, which the extension API does not offer. The raw pages are kept so the
     * shelves can be rebuilt against the database (for the in-library tick) without going back to
     * the network.
     */
    private val rawPopular = MutableStateFlow<List<SManga>>(emptyList())
    private val rawLatest = MutableStateFlow<List<SManga>>(emptyList())
    private val rawGenres = MutableStateFlow<List<Pair<String, List<SManga>>>>(emptyList())
    private val feedRefresh = MutableStateFlow(0)

    val feed = combine(rawPopular, rawLatest, rawGenres, feedRefresh) { popular, latest, genreRows, _ ->
        if (popular.isEmpty() && latest.isEmpty()) return@combine SourceFeed()
        val popularItems = popular.toDomain()
        val latestItems = latest.toDomain()
        SourceFeed(
            hero = popularItems.firstOrNull(),
            top = popularItems.take(TOP_SHELF_SIZE),
            shelves = listOfNotNull(
                latestItems.takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf("MỚI CẬP NHẬT", "", it, listing = Listing.Latest) },
                popularItems.filter { it.status == SManga.ONGOING.toLong() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf("ĐANG RA", "", it) },
                popularItems.filter { it.status == SManga.COMPLETED.toLong() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf("HOÀN THÀNH · CÀY TRỌN BỘ", "", it) },
            ) + genreRows.mapNotNull { (genre, entries) ->
                entries.takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf(genre.uppercase(), "", it.toDomain(), genre = genre) }
            },
            genres = (source as? CatalogueSource)?.genreNames().orEmpty(),
            loaded = true,
        )
    }.stateIn(ioCoroutineScope, SharingStarted.Lazily, SourceFeed())

    private suspend fun List<SManga>.toDomain(): List<Manga> =
        networkToLocalManga(map { it.toDomainManga(sourceId) })

    fun loadFeed() {
        if (rawPopular.value.isNotEmpty()) return
        screenModelScope.launchIO {
            // Independent so a source whose latest feed is broken still shows the rest.
            runCatching { source.getPopularManga(1).mangas }
                .onSuccess { rawPopular.value = it }
            if (source.supportsLatest) {
                runCatching { source.getLatestUpdates(1).mangas }
                    .onSuccess { rawLatest.value = it }
            }
            loadGenreShelves()
        }
    }

    /**
     * One shelf per popular genre, appended as they arrive.
     *
     * Each is its own request — the API has no way to ask for several genres at once — so they
     * load after the main feed is already on screen rather than holding it up, and a genre that
     * fails is simply left out.
     */
    private suspend fun loadGenreShelves() {
        val catalogue = source as? CatalogueSource ?: return
        val declared = catalogue.genreNames()
        if (declared.isEmpty()) return
        val chosen = PREFERRED_SHELF_GENRES.filter { it in declared }
            .ifEmpty { declared }
            .take(GENRE_SHELF_COUNT)
        chosen.forEach { genre ->
            // A source with a real genre filter gets its own listing; one without still gets a
            // shelf, from searching the genre's name. Weaker, but better than no shelf at all.
            val filters = filtersForGenre(genre)
            val query = if (filters == null) genre else ""
            runCatching {
                catalogue.getSearchManga(1, query, filters ?: catalogue.getFilterList()).mangas
            }
                .onSuccess { entries ->
                    if (entries.isNotEmpty()) rawGenres.value = rawGenres.value + (genre to entries)
                }
        }
    }

    /** A filter list with [genreName] picked, or null when the source has no such genre. */
    private fun filtersForGenre(genreName: String): FilterList? {
        val catalogue = source as? CatalogueSource ?: return null
        val filters = catalogue.getFilterList()
        for (sourceFilter in filters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && GenreFilterMatcher.namesMatch(filter.name, genreName)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> return null
                        }
                        return filters
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = GenreFilterMatcher.selectIndex(
                    sourceFilter.values.filterIsInstance<String>(),
                    genreName,
                )
                if (index != -1) {
                    sourceFilter.state = index
                    return filters
                }
            } else if (sourceFilter is SourceModelFilter.Text &&
                GenreFilterMatcher.isTagTextFilter(sourceFilter.name)
            ) {
                sourceFilter.state = genreName
                return filters
            }
        }
        return null
    }

    /** Opens a feed as a full grid — what `Tất cả ›` on a shelf header does. */
    fun showAll(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null, activeGenre = null, showGrid = true) }
    }

    /** Re-reads the feed's entries from the database, so the in-library tick stays honest. */
    fun refreshFeedFavorites() {
        feedRefresh.value += 1
    }

    /**
     * The genres a source declares in its own filter list.
     *
     * A browse result carries no genre of its own — that arrives with the details fetch — so the
     * chips filter through the source instead of over what is already on screen.
     */
    private fun CatalogueSource.genreNames(): List<String> = runCatching {
        getFilterList()
            .firstOrNull { filter ->
                filter.name.contains("thể loại", true) || filter.name.contains("genre", true)
            }
            .let { filter ->
                when (filter) {
                    // A group of toggles, one per genre.
                    is SourceModelFilter.Group<*> -> filter.state
                        .filterIsInstance<SourceModelFilter<*>>()
                        .map { it.name }
                    // A single picker; its first entry is the "all" option, which is not a genre.
                    is SourceModelFilter.Select<*> -> filter.values.filterIsInstance<String>().drop(1)
                    else -> emptyList()
                }
            }
    }.getOrDefault(emptyList()).ifEmpty { KOTORI_COMMON_GENRES }

    @Immutable
    data class SourceFeed(
        val hero: Manga? = null,
        val top: List<Manga> = emptyList(),
        val shelves: List<SourceShelf> = emptyList(),
        val genres: List<String> = emptyList(),
        val loaded: Boolean = false,
    )

    @Immutable
    data class SourceShelf(
        val label: String,
        val sub: String,
        val items: List<Manga>,
        /** Set when `Tất cả ›` should open this genre in full. */
        val genre: String? = null,
        /** Set when `Tất cả ›` should open a source feed in full. */
        val listing: Listing? = null,
    )

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update {
            it.copy(listing = listing, toolbarQuery = null, activeGenre = null, showGrid = false)
        }
    }

    fun setFilters(filters: FilterList) {
        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    /**
     * Search for an author, artist or circle through the source's own creator filter.
     *
     * Falls back to a plain query when the source has none, which is all this used to do — and on a
     * catalogue that indexes creators separately from text, a plain query for a name returns
     * nothing whatsoever.
     */
    fun searchCreator(name: String, preferGroup: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val defaultFilters = source.getFilterList()

        val target = defaultFilters
            .filterIsInstance<SourceModelFilter.Text>()
            .map { it to GenreFilterMatcher.creatorFilterRank(it.name, preferGroup) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first

        target?.state = trimmed

        mutableState.update {
            val listing = if (target != null) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = trimmed, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
                activeGenre = trimmed,
            )
        }
    }

    fun searchGenre(genreName: String) {
        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && GenreFilterMatcher.namesMatch(filter.name, genreName)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = GenreFilterMatcher.selectIndex(
                    sourceFilter.values.filterIsInstance<String>(),
                    genreName,
                )

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        // Text filters are ranked rather than taken first-match: a source that splits tags by
        // namespace lists "Male Tags" before "Tags", and a tag dropped in the wrong namespace is
        // a 404 on the site, not a narrower search.
        if (!genreExists && !GenreFilterMatcher.isGenericChip(genreName)) {
            val target = defaultFilters
                .filterIsInstance<SourceModelFilter.Text>()
                .map { it to GenreFilterMatcher.tagFilterRank(it.name, genreName) }
                .filter { it.second >= 0 }
                .minByOrNull { it.second }
                ?.first
            if (target != null) {
                target.state = GenreFilterMatcher.strippedTag(genreName)
                genreExists = true
            }
        }

        // A tag listing on Hitomi (and similar) is language-wide on the website. Leaving the
        // source's own language selected (e.g. Vietnamese) turns the nozomi into a tiny subset
        // and the list looks finished after a handful of titles.
        if (genreExists) {
            defaultFilters.filterIsInstance<SourceModelFilter.Select<*>>().forEach { filter ->
                if (!filter.name.contains("lang", true) && !filter.name.contains("ngôn", true)) {
                    return@forEach
                }
                val all = GenreFilterMatcher.languageAllIndex(filter.values.filterIsInstance<String>())
                if (all != -1) filter.state = all
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
                activeGenre = genreName,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getDuplicateLibraryManga.invoke(manga)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        /** The genre chip currently applied, so the row can mark it and offer a way back. */
        val activeGenre: String? = null,
        /** `Tất cả ›` was used: show the current feed as a grid rather than as shelves. */
        val showGrid: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }

    companion object {
        private const val TOP_SHELF_SIZE = 10
    }
}
