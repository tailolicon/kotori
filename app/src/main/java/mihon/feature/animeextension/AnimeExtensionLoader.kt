package mihon.feature.animeextension

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

/**
 * Loads installed Aniyomi-format anime extensions (shared installs).
 * Mirrors Mihon's manga ExtensionLoader with the anime feature/meta-data.
 */
internal object AnimeExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val trustExtension: TrustExtension by injectLazy()
    private val loadNsfwSource by lazy { preferences.showNsfwSource.get() }

    const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
    private const val METADATA_NSFW = "tachiyomi.animeextension.nsfw"

    /** Aniyomi extensions currently ship lib 12–16. */
    private val SUPPORTED_LIB_RANGE = 12.0..16.99

    @Suppress("DEPRECATION")
    val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    fun loadExtensions(context: Context): List<AnimeLoadResult> {
        val pkgManager = context.packageManager
        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }
        val extPkgs = installedPkgs.filter { isPackageAnExtension(it) }
        if (extPkgs.isEmpty()) return emptyList()

        return runBlocking {
            extPkgs.map { async { loadExtension(context, it) } }.awaitAll()
        }
    }

    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): AnimeLoadResult {
        val pkgInfo = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
        } catch (_: PackageManager.NameNotFoundException) {
            return AnimeLoadResult.Error
        }
        if (!isPackageAnExtension(pkgInfo)) return AnimeLoadResult.Error
        return loadExtension(context, pkgInfo)
    }

    @SuppressLint("PackageManagerGetSignatures")
    private suspend fun loadExtension(context: Context, pkgInfo: PackageInfo): AnimeLoadResult {
        val pkgManager = context.packageManager
        val appInfo = pkgInfo.applicationInfo ?: return AnimeLoadResult.Error
        val pkgName = pkgInfo.packageName

        val extName = pkgManager.getApplicationLabel(appInfo).toString()
            .substringAfter("Aniyomi: ")
        val versionName = pkgInfo.versionName ?: return AnimeLoadResult.Error
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_RANGE) {
            logcat(LogPriority.WARN) { "Anime extension lib version $libVersion unsupported ($pkgName)" }
            return AnimeLoadResult.Error
        }

        val signatures = getSignatures(pkgInfo)
        if (signatures.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Anime extension $pkgName isn't signed" }
            return AnimeLoadResult.Error
        } else if (!trustExtension.isTrusted(pkgInfo, signatures)) {
            return AnimeLoadResult.Untrusted(
                AnimeExtension.Untrusted(extName, pkgName, versionName, versionCode, signatures.last()),
            )
        }

        val isNsfw = appInfo.metaData.getInt(METADATA_NSFW) == 1
        if (!loadNsfwSource && isNsfw) {
            return AnimeLoadResult.Error
        }

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Anime extension load error: $extName ($pkgName)" }
            return AnimeLoadResult.Error
        }

        val sources = appInfo.metaData.getString(METADATA_SOURCE_CLASS)
            .orEmpty()
            .split(";")
            .filter { it.isNotBlank() }
            .map { sourceClass ->
                val trimmed = sourceClass.trim()
                if (trimmed.startsWith(".")) pkgName + trimmed else trimmed
            }
            .flatMap { className ->
                try {
                    when (val obj = Class.forName(className, false, classLoader)
                        .getDeclaredConstructor().newInstance()
                    ) {
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> {
                            logcat(LogPriority.ERROR) { "Unknown anime source class type $className" }
                            emptyList()
                        }
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to instantiate anime source $className" }
                    emptyList()
                }
            }

        if (sources.isEmpty()) return AnimeLoadResult.Error

        val lang = sources.filterIsInstance<eu.kanade.tachiyomi.animesource.AnimeCatalogueSource>()
            .map { it.lang }
            .distinct()
            .let { langs ->
                when {
                    langs.isEmpty() -> "all"
                    langs.size == 1 -> langs.first()
                    else -> "all"
                }
            }

        return AnimeLoadResult.Success(
            AnimeExtension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                lang = lang,
                sources = sources,
                isNsfw = isNsfw,
            ),
        )
    }

    fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return signatures
            ?.map { eu.kanade.tachiyomi.util.lang.Hash.sha256(it.toByteArray()) }
            ?.toList()
    }
}
