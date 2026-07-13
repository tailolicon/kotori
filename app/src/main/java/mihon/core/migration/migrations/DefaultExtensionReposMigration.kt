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
 * Seeds the default manga (Keiyoushi) and anime (yuzono) extension repositories on first run so a
 * fresh install has working sources immediately. Runs on every startup (isAlways, since fresh
 * installs only execute isAlways migrations) but only does the work once, guarded by a preference
 * flag that is set after the anime repo — the one that needs network — is successfully added.
 */
class DefaultExtensionReposMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val sourcePreferences = migrationContext.get<SourcePreferences>() ?: return@withIOContext false
        if (sourcePreferences.defaultReposSeeded.get()) return@withIOContext true

        // Manga: Keiyoushi — network-free upsert into the extension store (idempotent by index url).
        migrationContext.get<ExtensionStoreRepository>()?.let { store ->
            try {
                store.insertFromPreference(
                    indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
                    name = "Keiyoushi",
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed seeding default manga repo (Keiyoushi)" }
            }
        }

        // Anime: yuzono — fetches the repo's signing key over the network, then inserts.
        var animeSeeded = true
        migrationContext.get<CreateAnimeExtensionRepo>()?.let { create ->
            animeSeeded = try {
                when (create.await("https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json")) {
                    is CreateAnimeExtensionRepo.Result.Success,
                    is CreateAnimeExtensionRepo.Result.RepoAlreadyExists,
                    -> true
                    else -> false
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed seeding default anime repo (yuzono)" }
                false
            }
        }

        // Retry next launch (manga upsert stays idempotent) until the anime repo is in place.
        if (animeSeeded) sourcePreferences.defaultReposSeeded.set(true)
        return@withIOContext true
    }
}
