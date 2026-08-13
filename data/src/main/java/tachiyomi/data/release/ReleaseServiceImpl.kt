package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        if (arguments.updateManifestUrl.isNotBlank()) {
            return latestFromKotoriFeed(arguments.updateManifestUrl)
        }

        val release = with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadLink = getDownloadLink(release = release, isFoss = arguments.isFoss) ?: return null

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
        )
    }

    private suspend fun latestFromKotoriFeed(manifestUrl: String): Release? {
        val manifest = with(json) {
            networkService.client
                .newCall(GET(manifestUrl))
                .awaitSuccess()
                .parseAs<KotoriReleaseManifest>()
        }
        if (manifest.schema != KOTORI_MANIFEST_SCHEMA || manifest.assets.isEmpty()) return null

        val asset = manifest.selectAsset(Build.SUPPORTED_ABIS.toList()) ?: return null

        val base = manifestUrl.toHttpUrl()
        val downloadUrl = base.resolve(asset.url)?.toString() ?: return null
        val releaseUrl = manifest.releaseUrl.takeIf(String::isNotBlank)?.let(base::resolve)?.toString().orEmpty()

        return Release(
            version = manifest.versionName,
            info = manifest.changelog,
            releaseLink = releaseUrl,
            downloadLink = downloadUrl,
            versionCode = manifest.versionCode,
            sha256 = asset.sha256.lowercase(),
            size = asset.size,
        )
    }

    private fun getDownloadLink(release: GithubRelease, isFoss: Boolean): String? {
        val map = release.assets.associate { asset ->
            BUILD_TYPES.find { "-$it" in asset.name } to asset.downloadLink
        }

        return if (!isFoss) {
            map[Build.SUPPORTED_ABIS[0]] ?: map[null]
        } else {
            map[FOSS]
        }
    }

    companion object {
        private const val FOSS = "foss"
        private const val KOTORI_MANIFEST_SCHEMA = 1
        private val BUILD_TYPES = listOf(FOSS, "arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}
