package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview,
            arguments.commitCount,
            arguments.versionCode,
            arguments.versionName,
            release,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionCode: Long,
        versionName: String,
        release: Release,
    ): Boolean {
        release.versionCode?.let { return it > versionCode }

        // Removes prefixes like "r" or "v"
        val newVersion = release.version.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) {
            // Preview builds: based on releases in "mihonapp/mihon-preview" repo
            // tagged as something like "r1234"
            newVersion.toInt() > commitCount
        } else {
            // Release builds: based on releases in "mihonapp/mihon" repo
            // tagged as something like "v0.1.2"
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            compareVersionParts(newVersion, oldVersion) > 0
        }
    }

    private fun compareVersionParts(newVersion: String, oldVersion: String): Int {
        val newParts = newVersion.split('.').mapNotNull(String::toIntOrNull)
        val oldParts = oldVersion.split('.').mapNotNull(String::toIntOrNull)
        val count = maxOf(newParts.size, oldParts.size)
        repeat(count) { index ->
            val comparison = (newParts.getOrElse(index) { 0 }).compareTo(oldParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    data class Arguments(
        val isFoss: Boolean,
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
        val versionCode: Long = 0,
        /** A Kotori update manifest. Blank keeps the legacy GitHub release source available. */
        val updateManifestUrl: String = "",
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
