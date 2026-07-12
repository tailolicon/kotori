package mihon.feature.animeextension

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Manages Aniyomi-format anime extensions: discovers installed APKs, fetches
 * repo `index.min.json` catalogs, downloads and installs updates.
 */
class AnimeExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network: NetworkHelper by lazy { Injekt.get() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _installedFlow = MutableStateFlow(emptyList<AnimeExtension.Installed>())
    val installedFlow: StateFlow<List<AnimeExtension.Installed>> = _installedFlow.asStateFlow()

    private val _untrustedFlow = MutableStateFlow(emptyList<AnimeExtension.Untrusted>())
    val untrustedFlow: StateFlow<List<AnimeExtension.Untrusted>> = _untrustedFlow.asStateFlow()

    private val _availableFlow = MutableStateFlow(emptyList<AnimeExtension.Available>())
    val availableFlow: StateFlow<List<AnimeExtension.Available>> = _availableFlow.asStateFlow()

    val sources: List<AnimeSource>
        get() = _installedFlow.value.flatMap { it.sources }

    fun getSource(id: Long): AnimeSource? = sources.find { it.id == id }

    init {
        refreshInstalled()
    }

    fun refreshInstalled() {
        scope.launch {
            val results = AnimeExtensionLoader.loadExtensions(context)
            _installedFlow.value = results
                .filterIsInstance<AnimeLoadResult.Success>()
                .map { it.extension }
                .withUpdateFlags(_availableFlow.value)
            _untrustedFlow.value = results
                .filterIsInstance<AnimeLoadResult.Untrusted>()
                .map { it.extension }
        }
    }

    suspend fun refreshAvailable(): Result<Int> = withContext(Dispatchers.IO) {
        val repos = preferences.animeExtensionRepos.get().ifEmpty { setOf(DEFAULT_REPO) }
        val all = mutableListOf<AnimeExtension.Available>()
        var failures = 0
        repos.forEach { repo ->
            val base = repo.trimEnd('/')
            val indexUrl = if (base.endsWith(".json")) base else "$base/index.min.json"
            runCatching {
                val response = network.client.newCall(GET(indexUrl)).awaitSuccess()
                val entries = response.body.byteStream().use {
                    @Suppress("OPT_IN_USAGE")
                    json.decodeFromStream<List<RepoExtension>>(it)
                }
                val repoRoot = indexUrl.substringBeforeLast('/')
                entries.forEach { entry ->
                    all += AnimeExtension.Available(
                        name = entry.name.substringAfter("Aniyomi: "),
                        pkgName = entry.pkg,
                        versionName = entry.version,
                        versionCode = entry.code,
                        lang = entry.lang,
                        isNsfw = entry.nsfw == 1,
                        apkName = entry.apk,
                        repoUrl = repoRoot,
                        iconUrl = "$repoRoot/icon/${entry.pkg}.png",
                    )
                }
            }.onFailure {
                failures++
                logcat(LogPriority.ERROR, it) { "Failed to fetch anime repo $indexUrl" }
            }
        }
        _availableFlow.value = all.distinctBy { it.pkgName }
        _installedFlow.value = _installedFlow.value.withUpdateFlags(_availableFlow.value)
        if (all.isEmpty() && failures > 0) {
            Result.failure(Exception("Không tải được kho tiện ích"))
        } else {
            Result.success(all.size)
        }
    }

    /** Downloads the APK then hands it to the system installer. */
    suspend fun installExtension(extension: AnimeExtension.Available): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apkUrl = "${extension.repoUrl}/apk/${extension.apkName}"
                val response = network.client.newCall(GET(apkUrl)).awaitSuccess()
                val dir = File(context.cacheDir, "anime_ext").apply { mkdirs() }
                val apkFile = File(dir, extension.apkName)
                response.body.source().use { source ->
                    apkFile.sink().buffer().use { it.writeAll(source) }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".provider",
                    apkFile,
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        }

    fun uninstallExtension(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$pkgName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun List<AnimeExtension.Installed>.withUpdateFlags(
        available: List<AnimeExtension.Available>,
    ): List<AnimeExtension.Installed> {
        val byPkg = available.associateBy { it.pkgName }
        return map { installed ->
            val remote = byPkg[installed.pkgName]
            installed.copy(hasUpdate = remote != null && remote.versionCode > installed.versionCode)
        }
    }

    @Serializable
    private data class RepoExtension(
        val name: String,
        val pkg: String,
        val apk: String,
        val lang: String,
        val code: Long,
        val version: String,
        val nsfw: Int = 0,
    )

    companion object {
        const val DEFAULT_REPO = "https://raw.githubusercontent.com/yuzono/anime-repo/repo"
    }
}
