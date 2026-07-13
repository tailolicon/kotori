package eu.kanade.tachiyomi.di

import android.app.Application
import java.util.concurrent.Executors
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.DefaultXmlSerializationPolicy
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlConfig
import dataanime.Animehistory
import dataanime.Animes
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadCache
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadProvider
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.source.anime.AndroidAnimeSourceManager
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.mi.data.AnimeDatabase
import tachiyomi.source.local.entries.anime.LocalAnimeFetchTypeManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.anime.LocalAnimeBackgroundManager
import tachiyomi.source.local.image.anime.LocalAnimeCoverManager
import tachiyomi.source.local.image.anime.LocalEpisodeThumbnailManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.io.anime.LocalAnimeSourceFileSystem
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

private val lock = Any()

class AppModule(val app: Application) : InjektModule {

    private var sqlDriverRef: WeakReference<SqlDriver>? = null
    private var animeSqlDriverRef: WeakReference<SqlDriver>? = null

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        addSingletonFactory<SqlDriver> {
            synchronized(lock) {
                sqlDriverRef?.get()?.let { return@synchronized it }

                AndroidxSqliteDriver(
                    driver = BundledSQLiteDriver(),
                    databaseType = AndroidxSqliteDatabaseType.FileProvider(app, "tachiyomi.db"),
                    schema = Database.Schema,
                    configuration = AndroidxSqliteConfiguration(
                        isForeignKeyConstraintsEnabled = true,
                    ),
                )
                    .also { sqlDriverRef = WeakReference(it) }
            }
        }
        addSingletonFactory {
            Database(
                driver = get(),
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(
                    memoAdapter = MemoColumnAdapter,
                ),
            )
        }

        // region Anime database
        // Lazily create the anime SQLite driver on first use (which happens off the main thread)
        // instead of eagerly at module-registration time — the eager `val` opened the DB on the
        // main thread during App.onCreate and janked cold start.
        fun animeSqlDriver(): SqlDriver = synchronized(lock) {
            animeSqlDriverRef?.get() ?: AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.FileProvider(app, "tachiyomi.animedb"),
                schema = AnimeDatabase.Schema,
                configuration = AndroidxSqliteConfiguration(
                    isForeignKeyConstraintsEnabled = true,
                ),
            ).also { animeSqlDriverRef = WeakReference(it) }
        }
        addSingletonFactory {
            AnimeDatabase(
                driver = animeSqlDriver(),
                animehistoryAdapter = Animehistory.Adapter(
                    last_seenAdapter = DateColumnAdapter,
                ),
                animesAdapter = Animes.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = AnimeUpdateStrategyColumnAdapter,
                    fetch_typeAdapter = FetchTypeColumnAdapter,
                ),
            )
        }
        addSingletonFactory<AnimeDatabaseHandler> {
            AndroidAnimeDatabaseHandler(get(), animeSqlDriver())
        }
        // endregion

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory<XML> {
            XML.v1 {
                policy {
                    ignoreUnknownChildren()
                    autoPolymorphic = true
                }
                xmlDeclMode = XmlDeclMode.Charset
                xmlVersion = XmlVersion.XML10
                setIndent(2)
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }
        addSingletonFactory { AnimeCoverCache(app) }
        addSingletonFactory { AnimeBackgroundCache(app) }

        addSingletonFactory { NetworkHelper(app, get()) }
        addSingletonFactory { JavaScriptEngine(app) }

        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get()) }
        addSingletonFactory { ExtensionManager(app) }
        addSingletonFactory<AnimeSourceManager> { AndroidAnimeSourceManager(app, get(), get()) }
        addSingletonFactory { AnimeExtensionManager(app) }
        // Legacy stub anime-extension manager kept for Stremio / local-media / media-source features
        addSingletonFactory { mihon.feature.animeextension.AnimeExtensionManager(app) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }
        addSingletonFactory { AnimeDownloadProvider(app) }
        addSingletonFactory { AnimeDownloadManager(app) }
        addSingletonFactory { AnimeDownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }
        addSingletonFactory { DelayedAnimeTrackingStore(app) }

        addSingletonFactory { ImageSaver(app) }

        addSingletonFactory { AndroidStorageFolderProvider(app) }
        addSingletonFactory { LocalSourceFileSystem(get()) }
        addSingletonFactory { LocalCoverManager(app, get()) }
        addSingletonFactory { StorageManager(app, get()) }
        addSingletonFactory { LocalAnimeSourceFileSystem(get()) }
        addSingletonFactory { LocalAnimeCoverManager(app, get()) }
        addSingletonFactory { LocalAnimeBackgroundManager(app, get()) }
        addSingletonFactory { LocalEpisodeThumbnailManager(app, get()) }
        addSingletonFactory { LocalAnimeFetchTypeManager(app, get()) }

        addSingletonFactory { eu.kanade.tachiyomi.ui.player.ExternalIntents() }
        addSingletonFactory { aniyomi.core.common.torrent.TorrentServerApi(get(), get()) }
        addSingletonFactory { aniyomi.core.common.torrent.TorrentServerUtils(get(), get()) }
        // Shared extension-repo service used by the ported Aniyomi anime extension-repo interactors
        addSingletonFactory { mihon.domain.extensionrepo.service.ExtensionRepoService(get(), get()) }

        // Asynchronously init expensive components off the main thread for a faster cold start.
        // (These are IO-bound singletons — sources, SQLite drivers, download caches — that
        // previously ran on the main executor and janked startup, doubly so after the anime port.)
        Executors.newSingleThreadExecutor().execute {
            get<NetworkHelper>()

            get<SourceManager>()
            get<AnimeSourceManager>()

            get<Database>()
            get<AnimeDatabase>()

            get<DownloadManager>()
            get<AnimeDownloadManager>()
        }
    }
}
