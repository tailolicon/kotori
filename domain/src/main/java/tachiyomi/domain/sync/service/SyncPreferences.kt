package tachiyomi.domain.sync.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SyncPreferences(
    preferenceStore: PreferenceStore,
) {

    val syncEnabled: Preference<Boolean> = preferenceStore.getBoolean("sync_enabled", false)

    val syncUrl: Preference<String> = preferenceStore.getString("sync_url", "")

    /**
     * Must use [Preference.privateKey]: PreferenceBackupCreator filters out keys matching
     * [Preference.isPrivate], and the backup this feature produces gets uploaded to the very
     * WebDAV server those credentials open. Without the private prefix, anyone who obtains the
     * backup file also obtains the account.
     */
    val syncUsername: Preference<String> = preferenceStore.getString(
        Preference.privateKey("sync_username"),
        "",
    )

    /**
     * Must use [Preference.privateKey]: PreferenceBackupCreator filters out keys matching
     * [Preference.isPrivate], and the backup this feature produces gets uploaded to the very
     * WebDAV server those credentials open. Without the private prefix, anyone who obtains the
     * backup file also obtains the account.
     */
    val syncPassword: Preference<String> = preferenceStore.getString(
        Preference.privateKey("sync_password"),
        "",
    )

    val syncInterval: Preference<Int> = preferenceStore.getInt("sync_interval", 6)

    val lastSyncTimestamp: Preference<Long> = preferenceStore.getLong("last_sync_timestamp", 0L)
}
