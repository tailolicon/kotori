package tachiyomi.data.updates

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

class UpdatesRepositoryImpl(
    private val database: Database,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<UpdatesWithRelations> {
        return database.updatesViewQueries
            .getUpdatesByReadStatus(
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
            .awaitAsList()
    }

    override fun subscribeAll(
        after: Long,
        limit: Long,
        novelSourceIds: List<Long>,
        onlyNovelSources: Boolean,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
    ): Flow<List<UpdatesWithRelations>> {
        return database.updatesViewQueries
            .getRecentUpdatesWithFilters(
                after = after,
                limit = limit,
                // `IN ()` is a syntax error, and an id no source can have keeps the comparison
                // honest: nothing is a novel until the source list has actually loaded.
                novelSourceIds = novelSourceIds.ifEmpty { listOf(-1L) },
                onlyNovelSources = onlyNovelSources,
                read = unread?.let { !it },
                started = started?.toLong(),
                bookmarked = bookmarked,
                hideExcludedScanlators = hideExcludedScanlators.toLong(),
                mapper = ::mapUpdatesWithRelations,
            )
            .subscribeToList()
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<UpdatesWithRelations>> {
        return database.updatesViewQueries
            .getUpdatesByReadStatus(
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
            .subscribeToList()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        dateUpload: Long,
        dateFetch: Long,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
