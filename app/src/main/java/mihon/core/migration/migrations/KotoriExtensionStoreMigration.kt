package mihon.core.migration.migrations

import eu.kanade.domain.source.service.SourcePreferences
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository
import mihon.domain.extensionrepo.anime.interactor.CreateAnimeExtensionRepo
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Adds the Kotori extension stores on every install that does not already have them.
 *
 * Seeded separately from [DefaultExtensionReposMigration] so existing readers, who already ran that
 * one-shot seed, still pick these up — and the two stores are guarded by a flag each for the same
 * reason: the anime store arrived after the manga one, and anyone already carrying
 * `kotoriStoreSeeded` would otherwise never be offered it. That is not hypothetical; the sources
 * that used to be built into the app moved into this store, so a reader without it simply loses
 * AnimeHay and AnimeVietsub.
 */
class KotoriExtensionStoreMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val prefs = migrationContext.get<SourcePreferences>() ?: return@withIOContext false

        if (!prefs.kotoriStoreSeeded.get()) {
            val store = migrationContext.get<ExtensionStoreRepository>() ?: return@withIOContext false
            val already = store.getAll().any { it.indexUrl == INDEX_URL || it.name.equals("Kotori", true) }
            if (!already) {
                store.insertFromPreference(
                    indexUrl = INDEX_URL,
                    name = "Kotori",
                    signingKey = SIGNING_KEY,
                )
            }
            prefs.kotoriStoreSeeded.set(true)
        }

        // Needs the network to read the repo's signing key, so it can fail offline. Leaving the flag
        // unset simply retries on the next launch; adding the same repo twice is a no-op.
        if (!prefs.kotoriAnimeStoreSeeded.get()) {
            val create = migrationContext.get<CreateAnimeExtensionRepo>() ?: return@withIOContext true
            val seeded = try {
                when (create.await(ANIME_INDEX_URL)) {
                    is CreateAnimeExtensionRepo.Result.Success,
                    is CreateAnimeExtensionRepo.Result.RepoAlreadyExists,
                    -> true
                    else -> false
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed seeding the Kotori anime store" }
                false
            }
            if (seeded) prefs.kotoriAnimeStoreSeeded.set(true)
        }

        true
    }

    companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo/index.pb"

        const val ANIME_INDEX_URL =
            "https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo-anime/index.min.json"

        const val SIGNING_KEY = "4cc9ab1cd650537c42c39582fa22eb5012029d56f9f4483f9ea9d073b4f9c779"
    }
}
