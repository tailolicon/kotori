package mihon.feature.animeextension

import eu.kanade.tachiyomi.animesource.AnimeSource

/**
 * Anime extension models (Aniyomi ecosystem: feature `tachiyomi.animeextension`,
 * repo `index.min.json` identical in shape to manga repos).
 */
sealed class AnimeExtension {
    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    abstract val lang: String?

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String,
        val sources: List<AnimeSource>,
        val isNsfw: Boolean,
        val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
    ) : AnimeExtension()

    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        val signatureHash: String,
        override val lang: String? = null,
    ) : AnimeExtension()

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String,
        val isNsfw: Boolean,
        val apkName: String,
        val repoUrl: String,
        val iconUrl: String,
    ) : AnimeExtension()
}

sealed class AnimeLoadResult {
    data class Success(val extension: AnimeExtension.Installed) : AnimeLoadResult()
    data class Untrusted(val extension: AnimeExtension.Untrusted) : AnimeLoadResult()
    data object Error : AnimeLoadResult()
}
