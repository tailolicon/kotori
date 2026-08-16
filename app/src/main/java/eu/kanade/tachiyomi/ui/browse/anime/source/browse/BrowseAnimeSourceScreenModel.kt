package eu.kanade.tachiyomi.ui.browse.anime.source.browse

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
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.util.removeBackgrounds
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.anime.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.toAnimeUpdate
import tachiyomi.domain.items.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.tachiyomi.animesource.model.AnimeFilter as AnimeSourceModelFilter
import eu.kanade.tachiyomi.ui.browse.GenreFilterMatcher

class BrowseAnimeSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: AnimeSourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: AnimeCoverCache = Injekt.get(),
    private val backgroundCache: AnimeBackgroundCache = Injekt.get(),
    private val getRemoteAnime: GetRemoteAnime = Injekt.get(),
    private val getDuplicateAnimelibAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val addTracks: AddAnimeTracks = Injekt.get(),
    private val getIncognitoState: GetAnimeIncognitoState = Injekt.get(),
) : StateScreenModel<BrowseAnimeSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        if (source is AnimeCatalogueSource) {
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
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedAnimeSource().set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInAnimeLibraryItems().get()
    val animePagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteAnime.subscribe(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map {
                    networkToLocalAnime.await(it.toDomainAnime(sourceId))
                        .let { localAnime -> getAnime.subscribe(localAnime.url, localAnime.source) }
                        .filterNotNull()
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
    private val rawPopular = MutableStateFlow<List<SAnime>>(emptyList())
    private val rawLatest = MutableStateFlow<List<SAnime>>(emptyList())
    private val rawGenres = MutableStateFlow<List<Pair<String, List<SAnime>>>>(emptyList())
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
                popularItems.filter { it.status == SAnime.ONGOING.toLong() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf("ĐANG CHIẾU MÙA NÀY", currentSeasonLabel(), it) },
                popularItems.filter { it.status == SAnime.COMPLETED.toLong() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf("HOÀN THÀNH · CÀY TRỌN BỘ", "", it) },
            ) + genreRows.mapNotNull { (genre, entries) ->
                entries.takeIf { it.isNotEmpty() }
                    ?.let { SourceShelf(genre.uppercase(), "", it.toDomain(), genre = genre) }
            },
            genres = (source as? AnimeCatalogueSource)?.genreNames().orEmpty(),
            loaded = true,
        )
    }.stateIn(ioCoroutineScope, SharingStarted.Lazily, SourceFeed())

    private suspend fun List<SAnime>.toDomain(): List<Anime> = map {
        networkToLocalAnime.await(it.toDomainAnime(sourceId))
    }

    fun loadFeed() {
        val catalogue = source as? AnimeCatalogueSource ?: return
        if (rawPopular.value.isNotEmpty()) return
        screenModelScope.launchIO {
            // Independent so a source whose latest feed is broken still shows the rest.
            runCatching { catalogue.getPopularAnime(1).animes }
                .onSuccess { rawPopular.value = it }
            if (catalogue.supportsLatest) {
                runCatching { catalogue.getLatestUpdates(1).animes }
                    .onSuccess { rawLatest.value = it }
            }
            loadGenreShelves(catalogue)
        }
    }

    /**
     * One shelf per popular genre, appended as they arrive.
     *
     * Each is its own request — the API has no way to ask for several genres at once — so they
     * load after the main feed is already on screen rather than holding it up, and a genre that
     * fails is simply left out.
     */
    private suspend fun loadGenreShelves(catalogue: AnimeCatalogueSource) {
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
                catalogue.getSearchAnime(1, query, filters ?: catalogue.getFilterList()).animes
            }
                .onSuccess { entries ->
                    if (entries.isNotEmpty()) rawGenres.value = rawGenres.value + (genre to entries)
                }
        }
    }

    /** A filter list with [genreName] picked, or null when the source has no such genre. */
    private fun filtersForGenre(genreName: String): AnimeFilterList? {
        val catalogue = source as? AnimeCatalogueSource ?: return null
        val filters = catalogue.getFilterList()
        for (sourceFilter in filters) {
            if (sourceFilter is AnimeSourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is AnimeSourceModelFilter<*> &&
                        GenreFilterMatcher.namesMatch(filter.name, genreName)
                    ) {
                        when (filter) {
                            is AnimeSourceModelFilter.TriState -> filter.state = 1
                            is AnimeSourceModelFilter.CheckBox -> filter.state = true
                            else -> return null
                        }
                        return filters
                    }
                }
            } else if (sourceFilter is AnimeSourceModelFilter.Select<*>) {
                val index = GenreFilterMatcher.selectIndex(
                    sourceFilter.values.filterIsInstance<String>(),
                    genreName,
                )
                if (index != -1) {
                    sourceFilter.state = index
                    return filters
                }
            } else if (sourceFilter is AnimeSourceModelFilter.Text &&
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
    private fun AnimeCatalogueSource.genreNames(): List<String> = runCatching {
        getFilterList()
            .firstOrNull { filter ->
                filter.name.contains("thể loại", true) || filter.name.contains("genre", true)
            }
            .let { filter ->
                when (filter) {
                    // A group of toggles, one per genre.
                    is AnimeSourceModelFilter.Group<*> -> filter.state
                        .filterIsInstance<AnimeSourceModelFilter<*>>()
                        .map { it.name }
                    // A single picker; its first entry is the "all" option, which is not a genre.
                    is AnimeSourceModelFilter.Select<*> -> filter.values.filterIsInstance<String>().drop(1)
                    else -> emptyList()
                }
            }
    }.getOrDefault(emptyList()).ifEmpty { KOTORI_COMMON_GENRES }

    @Immutable
    data class SourceFeed(
        val hero: Anime? = null,
        val top: List<Anime> = emptyList(),
        val shelves: List<SourceShelf> = emptyList(),
        val genres: List<String> = emptyList(),
        val loaded: Boolean = false,
    )

    @Immutable
    data class SourceShelf(
        val label: String,
        val sub: String,
        val items: List<Anime>,
        /** Set when `Tất cả ›` should open this genre in full. */
        val genre: String? = null,
        /** Set when `Tất cả ›` should open a source feed in full. */
        val listing: Listing? = null,
    )

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.animeLandscapeColumns()
        } else {
            libraryPreferences.animePortraitColumns()
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // returns the number from the size slider
    fun getColumnsPreferenceForCurrentOrientation(orientation: Int): Int {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            libraryPreferences.animeLandscapeColumns()
        } else {
            libraryPreferences.animePortraitColumns()
        }.get()
    }

    fun resetFilters() {
        if (source !is AnimeCatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update {
            it.copy(listing = listing, toolbarQuery = null, activeGenre = null, showGrid = false)
        }
    }

    fun setFilters(filters: AnimeFilterList) {
        if (source !is AnimeCatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: AnimeFilterList? = null) {
        if (source !is AnimeCatalogueSource) return

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

    fun searchGenre(genreName: String) {
        if (source !is AnimeCatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is AnimeSourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is AnimeSourceModelFilter<*> &&
                        GenreFilterMatcher.namesMatch(filter.name, genreName)
                    ) {
                        when (filter) {
                            is AnimeSourceModelFilter.TriState -> filter.state = 1
                            is AnimeSourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is AnimeSourceModelFilter.Select<*>) {
                val index = GenreFilterMatcher.selectIndex(
                    sourceFilter.values.filterIsInstance<String>(),
                    genreName,
                )

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            } else if (sourceFilter is AnimeSourceModelFilter.Text &&
                GenreFilterMatcher.isTagTextFilter(sourceFilter.name)
            ) {
                sourceFilter.state = genreName
                genreExists = true
                break
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
     * Adds or removes an anime from the library.
     *
     * @param anime the anime to update.
     */
    fun changeAnimeFavorite(anime: Anime) {
        screenModelScope.launch {
            var new = anime.copy(
                favorite = !anime.favorite,
                dateAdded = when (anime.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
                new = new.removeBackgrounds(backgroundCache)
            } else {
                setAnimeDefaultEpisodeFlags.await(anime)
                addTracks.bindEnhancedTrackers(anime, source)
            }

            updateAnime.await(new.toAnimeUpdate())
        }
    }

    fun addFavorite(anime: Anime) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultAnimeCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveAnimeToCategories(anime, defaultCategory)

                    changeAnimeFavorite(anime)
                }
                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveAnimeToCategories(anime)

                    changeAnimeFavorite(anime)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(anime.id).map { it.id }
                    setDialog(
                        Dialog.ChangeAnimeCategory(
                            anime,
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
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

    suspend fun getDuplicateAnimelibAnime(anime: Anime): Anime? {
        return getDuplicateAnimelibAnime.await(anime).getOrNull(0)
    }

    private fun moveAnimeToCategories(anime: Anime, vararg categories: Category) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveAnimeToCategories(anime: Anime, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(
                animeId = anime.id,
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

    sealed class Listing(open val query: String?, open val filters: AnimeFilterList) {
        data object Popular : Listing(
            query = GetRemoteAnime.QUERY_POPULAR,
            filters = AnimeFilterList(),
        )
        data object Latest : Listing(
            query = GetRemoteAnime.QUERY_LATEST,
            filters = AnimeFilterList(),
        )
        data class Search(override val query: String?, override val filters: AnimeFilterList) : Listing(
            query = query,
            filters = filters,
        )

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteAnime.QUERY_POPULAR -> Popular
                    GetRemoteAnime.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = AnimeFilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveAnime(val anime: Anime) : Dialog
        data class AddDuplicateAnime(val anime: Anime, val duplicate: Anime) : Dialog
        data class ChangeAnimeCategory(
            val anime: Anime,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val newAnime: Anime, val oldAnime: Anime) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: AnimeFilterList = AnimeFilterList(),
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

/**
 * `Hè 2026` and friends — the season the device is in right now.
 *
 * A source result says nothing about which season it belongs to, so this labels the shelf rather
 * than filtering it: the shelf itself is "still airing", which the entries do declare.
 */
private fun currentSeasonLabel(): String {
    val now = java.time.LocalDate.now()
    val season = when (now.monthValue) {
        1, 2, 3 -> "Đông"
        4, 5, 6 -> "Xuân"
        7, 8, 9 -> "Hè"
        else -> "Thu"
    }
    return "$season ${now.year}"
}

/**
 * Genres to offer when a source declares none of its own.
 *
 * These run as a plain search for the word, which is weaker than a real genre listing but is the
 * only thing available for a source that exposes no filters — and an empty chip row is worse.
 */
internal val KOTORI_COMMON_GENRES = listOf(
    "Hành động",
    "Phiêu lưu",
    "Hài hước",
    "Tình cảm",
    "Học đường",
    "Kinh dị",
    "Viễn tưởng",
    "Huyền ảo",
    "Trinh thám",
    "Thể thao",
    "Đời thường",
    "Kiếm hiệp",
)

/**
 * Genres worth a shelf of their own, best first.
 *
 * Intersected with what a source actually declares — a source with none of these falls back to
 * its own first few, so the feed is never short of shelves.
 */
internal val PREFERRED_SHELF_GENRES = listOf(
    "Hành động",
    "Tình cảm",
    "Hài hước",
    "Phiêu lưu",
    "Học đường",
    "Huyền ảo",
    "Kinh dị",
    "Viễn tưởng",
)

/** How many genre shelves the feed carries. Each one costs a request when the source opens. */
internal const val GENRE_SHELF_COUNT = 4
