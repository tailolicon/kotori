package mihon.core.migration.migrations

import eu.kanade.domain.source.service.SourcePreferences
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Adds the Kotori extension store (Hitomi and later sources) on every install that does not
 * already have it. Seeded separately from [DefaultExtensionReposMigration] so existing users
 * who already ran that one-shot seed still pick the store up.
 */
class KotoriExtensionStoreMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val prefs = migrationContext.get<SourcePreferences>() ?: return@withIOContext false
        if (prefs.kotoriStoreSeeded.get()) return@withIOContext true
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
        true
    }

    companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo/index.pb"

        const val SIGNING_KEY = "4cc9ab1cd650537c42c39582fa22eb5012029d56f9f4483f9ea9d073b4f9c779"
    }
}
