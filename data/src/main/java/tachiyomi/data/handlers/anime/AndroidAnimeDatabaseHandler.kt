package tachiyomi.data.handlers.anime

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import tachiyomi.mi.data.AnimeDatabase

class AndroidAnimeDatabaseHandler(
    val db: AnimeDatabase,
    private val driver: SqlDriver,
    val queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnimeDatabaseHandler {

    override suspend fun <T> await(inTransaction: Boolean, block: suspend AnimeDatabase.() -> T): T {
        return dispatch(inTransaction, block)
    }

    override suspend fun <T : Any> awaitList(
        inTransaction: Boolean,
        block: suspend AnimeDatabase.() -> Query<T>,
    ): List<T> {
        return dispatch(inTransaction) { block(db).awaitAsList() }
    }

    override suspend fun <T : Any> awaitOne(
        inTransaction: Boolean,
        block: suspend AnimeDatabase.() -> Query<T>,
    ): T {
        return dispatch(inTransaction) { block(db).awaitAsOne() }
    }

    override suspend fun <T : Any> awaitOneExecutable(
        inTransaction: Boolean,
        block: suspend AnimeDatabase.() -> ExecutableQuery<T>,
    ): T {
        return dispatch(inTransaction) { block(db).awaitAsOne() }
    }

    override suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean,
        block: suspend AnimeDatabase.() -> Query<T>,
    ): T? {
        return dispatch(inTransaction) { block(db).awaitAsOneOrNull() }
    }

    override suspend fun <T : Any> awaitOneOrNullExecutable(
        inTransaction: Boolean,
        block: suspend AnimeDatabase.() -> ExecutableQuery<T>,
    ): T? {
        return dispatch(inTransaction) { block(db).awaitAsOneOrNull() }
    }

    override fun <T : Any> subscribeToList(block: AnimeDatabase.() -> Query<T>): Flow<List<T>> {
        return block(db).asFlow().mapToList(queryDispatcher)
    }

    override fun <T : Any> subscribeToOne(block: AnimeDatabase.() -> Query<T>): Flow<T> {
        return block(db).asFlow().mapToOne(queryDispatcher)
    }

    override fun <T : Any> subscribeToOneOrNull(block: AnimeDatabase.() -> Query<T>): Flow<T?> {
        return block(db).asFlow().mapToOneOrNull(queryDispatcher)
    }

    override fun <T : Any> subscribeToPagingSource(
        countQuery: AnimeDatabase.() -> Query<Long>,
        queryProvider: AnimeDatabase.(Long, Long) -> Query<T>,
    ): PagingSource<Long, T> {
        return QueryPagingAnimeSource(
            handler = this,
            countQuery = countQuery,
            queryProvider = { limit, offset ->
                queryProvider.invoke(db, limit, offset)
            },
        )
    }

    private suspend fun <T> dispatch(inTransaction: Boolean, block: suspend AnimeDatabase.() -> T): T {
        if (inTransaction) {
            return db.transactionWithResult { block(db) }
        }
        return block(db)
    }
}
