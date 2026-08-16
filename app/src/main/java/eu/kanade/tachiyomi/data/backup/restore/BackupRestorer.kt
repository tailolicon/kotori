package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeCategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.source.anime.repository.AnimeStubSourceRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val database: Database = Injekt.get(),
    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val animeCategoriesRestorer: AnimeCategoriesRestorer = AnimeCategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val animeRestorer: AnimeRestorer = AnimeRestorer(),
) {

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        // Invalidate download cache to ensure UI reflects any restored downloads
        if (options.libraryEntries) {
            try {
                Injekt.get<DownloadCache>().invalidateCache()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to invalidate download cache after restore" }
            }
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        // Store source mapping for error messages
        sourceMapping = backup.backupSources.associate { it.sourceId to it.name } +
            backup.backupAnimeSources.associate { it.sourceId to it.name }

        // The backup is the only place a name survives for a source this device has no extension
        // for. Without writing them down, a restored library reported its missing sources as bare
        // ids — "3313733609433811176 · not found" tells a reader nothing about what to install.
        rememberSourceNames(backup.backupSources, backup.backupAnimeSources)

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size + backup.backupAnime.size
        }
        if (options.categories) {
            // One progress unit each for manga and anime category restore
            restoreAmount += 2
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            if (options.categories) {
                restoreCategories(backup.backupCategories)
                restoreAnimeCategories(backup.backupAnimeCategories)
            }
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences)
            }
            if (options.libraryEntries) {
                restoreManga(backup.backupManga, if (options.categories) backup.backupCategories else emptyList())
                restoreAnime(backup.backupAnime, if (options.categories) backup.backupAnimeCategories else emptyList())
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup.backupExtensionStores)
            }

            // TODO: optionally trigger online library + tracker update
        }
    }

    /**
     * Write every source the backup mentions into the stub tables.
     *
     * These are the rows [tachiyomi.domain.source.service.SourceManager.getOrStub] falls back to
     * when no extension provides a source, and they are what the Tiện ích screen lists under
     * missing sources. The backup does not carry a language per source, so the stub keeps whatever
     * language it already had — an installed extension overwrites the row with the real values the
     * moment it appears, so a name recovered here is never the one that wins.
     */
    private suspend fun rememberSourceNames(
        sources: List<eu.kanade.tachiyomi.data.backup.models.BackupSource>,
        animeSources: List<eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource>,
    ) {
        val stubSources = Injekt.get<StubSourceRepository>()
        val stubAnimeSources = Injekt.get<AnimeStubSourceRepository>()
        sources.filter { it.name.isNotBlank() }.forEach { source ->
            try {
                val existing = stubSources.getStubSource(source.sourceId)
                if (existing?.name == source.name) return@forEach
                stubSources.upsertStubSource(source.sourceId, existing?.lang.orEmpty(), source.name)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to remember source name for ${source.sourceId}" }
            }
        }
        animeSources.filter { it.name.isNotBlank() }.forEach { source ->
            try {
                val existing = stubAnimeSources.getStubAnimeSource(source.sourceId)
                if (existing?.name == source.name) return@forEach
                stubAnimeSources.upsertStubAnimeSource(source.sourceId, existing?.lang.orEmpty(), source.name)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to remember anime source name for ${source.sourceId}" }
            }
        }
    }

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreAnimeCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        animeCategoriesRestorer(backupCategories)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            .chunked(100)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            mangaRestorer.restore(it, backupCategories)
                        } catch (e: Exception) {
                            val sourceName = sourceMapping[it.source] ?: it.source.toString()
                            errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(chunk.last().title, restoreProgress.load(), restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreAnime(
        backupAnimes: List<BackupAnime>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        animeRestorer.sortByNew(backupAnimes)
            .chunked(100)
            .forEach { chunk ->
                // No `database.transaction` around this, unlike the manga path: anime lives in its
                // own SQLDelight database, and `AnimeRestorer.restore` already opens a transaction
                // on that handler. Wrapping it in the manga database's transaction would hold a
                // lock on the wrong database for the whole chunk and protect nothing.
                chunk.forEach {
                    ensureActive()

                    try {
                        // The backup carries no separate seasons list — seasons travel as ordinary
                        // entries linked by parent id — so there is nothing to hand over here.
                        animeRestorer.restore(it, backupCategories, backupSeasons = emptyList())
                    } catch (e: Exception) {
                        val sourceName = sourceMapping[it.source] ?: it.source.toString()
                        errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                    }

                    restoreProgress.incrementAndFetch()
                }
                notifier.showRestoreProgress(chunk.last().title, restoreProgress.load(), restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .chunked(100)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            extensionStoreRestorer(it)
                        } catch (e: Exception) {
                            errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress.load(),
                    restoreAmount,
                    isSync,
                )
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
