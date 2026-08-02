package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.sync.service.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class SyncJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)

    override suspend fun doWork(): Result = syncMutex.withLock { runSync() }

    private suspend fun runSync(): Result {
        val isManual = inputData.getBoolean(IS_MANUAL_KEY, false)
        val reason = inputData.getString(SYNC_REASON_KEY).orEmpty()
        val prefs = Injekt.get<SyncPreferences>()

        if (!prefs.syncEnabled.get() || prefs.syncUrl.get().isBlank()) {
            return Result.success()
        }

        // Only the sync the user pressed a button for announces itself. Opening and closing the
        // app would otherwise flash a notification every time.
        if (isManual) setForegroundSafely()

        var downloadFile: File? = null
        var uploadFile: File? = null

        return try {
            logcat { "WebDAV sync started ($reason)" }
            val client = WebDavSyncClient(prefs)

            // null = remote file missing (first ever sync) — skip restore and create it on upload
            val remoteBytes = client.download()
            if (remoteBytes != null) {
                downloadFile = File(context.cacheDir, DOWNLOAD_TEMP_NAME).also {
                    it.writeBytes(remoteBytes)
                }
                BackupRestorer(context, notifier, isSync = true)
                    .restore(downloadFile.toUniFileUri(), RESTORE_OPTIONS)
            }

            // Fresh backup of the now-merged local state, then push it upstream
            // BackupCreator rejects a destination that is not already a file, so the empty
            // placeholder has to exist on disk before it is handed over — a `File` object alone
            // is just a path, and the failure it produced said only "Couldn't create a backup
            // file", which points at the backup rather than at its destination.
            uploadFile = File(context.cacheDir, UPLOAD_TEMP_NAME).also {
                it.delete()
                it.createNewFile()
            }
            BackupCreator(context, isAutoBackup = false)
                .backup(uploadFile.toUniFileUri(), BACKUP_OPTIONS)
            val uploadBytes = uploadFile.readBytes()
            client.upload(uploadBytes)

            prefs.lastSyncTimestamp.set(System.currentTimeMillis())
            logcat { "WebDAV sync completed ($reason)" }

            // Snapshot is undo insurance only — never fail a sync that already succeeded.
            runCatching {
                val todayName = "kotori-sync-${LocalDate.now().format(SNAPSHOT_DATE_FORMAT)}.tachibk"
                val names = client.listFileNames()
                if (todayName !in names) {
                    client.upload(uploadBytes, todayName)
                }
                // yyyyMMdd sorts chronologically as a string, so descending = newest first.
                // Regex excludes the live kotori-sync.tachibk — that file is current state;
                // deleting it would look like a clean prune until the next device syncs.
                names
                    .filter { SNAPSHOT_NAME_REGEX.matches(it) }
                    .sortedDescending()
                    .drop(MAX_SNAPSHOTS)
                    .forEach { client.delete(it) }
            }.onFailure { e ->
                logcat(LogPriority.WARN, e)
            }

            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            if (isManual) {
                notifier.showRestoreError(e.message)
            }
            Result.failure()
        } finally {
            downloadFile?.delete()
            uploadFile?.delete()
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
            context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress(sync = true).build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val TAG_AUTO = "Sync"
        const val TAG_MANUAL = "Sync:manual"
        const val IS_MANUAL_KEY = "is_manual"
        const val SYNC_REASON_KEY = "sync_reason"

        // privateSettings must stay false: the uploaded backup lives on the same WebDAV server
        // whose credentials are stored under private preference keys.
        private val BACKUP_OPTIONS = BackupOptions(privateSettings = false)
        private val RESTORE_OPTIONS = RestoreOptions()

        private const val ONE_TIME_WORK_NAME = "Sync:once"

        private const val DOWNLOAD_TEMP_NAME = "kotori-sync-download.tachibk"
        private const val UPLOAD_TEMP_NAME = "kotori-sync-upload.tachibk"

        private const val MAX_SNAPSHOTS = 3
        private val SNAPSHOT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val SNAPSHOT_NAME_REGEX = Regex("""kotori-sync-\d{8}\.tachibk""")
        private val syncMutex = Mutex()

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val syncPreferences = Injekt.get<SyncPreferences>()
            val interval = prefInterval ?: syncPreferences.syncInterval.get()
            if (interval > 0) {
                val constraints = Constraints(
                    requiredNetworkType = NetworkType.CONNECTED,
                )

                val request = PeriodicWorkRequestBuilder<SyncJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG_AUTO)
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            IS_MANUAL_KEY to false,
                            SYNC_REASON_KEY to "periodic",
                        ),
                    )
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    TAG_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        /** The sync button in settings: always runs, and says so when it fails. */
        fun startNow(context: Context) = enqueueOnce(context, reason = "manual", isManual = true)

        /** Pull whenever the app enters the foreground so another device's upload is visible. */
        fun startOnOpen(context: Context) {
            if (!Injekt.get<SyncPreferences>().syncEnabled.get()) return
            enqueueOnce(context, reason = "open", isManual = false, expedited = true)
        }

        /**
         * Leaving the app — push what was just read.
         *
         * Not manual, because a phone that walks out of wifi would otherwise raise an error
         * notification every single time the app is closed.
         */
        fun startOnLeave(context: Context) {
            if (!Injekt.get<SyncPreferences>().syncEnabled.get()) return
            enqueueOnce(context, reason = "leave", isManual = false, expedited = true)
        }

        /**
         * Progress changed while the app is open — push it now rather than at exit.
         *
         * Waiting for `onStop` is a single point of failure: a force stop, or an OEM battery
         * manager that kills the process outright, skips it, and everything read in that session
         * stays on the one device. [App] samples progress writes before calling this, so continuous
         * reading still uploads without creating one job per database write.
         */
        fun startOnProgress(context: Context) {
            if (!Injekt.get<SyncPreferences>().syncEnabled.get()) return
            enqueueOnce(context, reason = "progress", isManual = false, expedited = true)
        }

        private fun enqueueOnce(
            context: Context,
            reason: String,
            isManual: Boolean,
            expedited: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<SyncJob>()
                .addTag(if (isManual) TAG_MANUAL else TAG_AUTO)
                .setConstraints(
                    Constraints(requiredNetworkType = NetworkType.CONNECTED),
                )
                .setInputData(
                    workDataOf(
                        IS_MANUAL_KEY to isManual,
                        SYNC_REASON_KEY to reason,
                    ),
                )
                .apply {
                    // The push on leaving races the process being killed, so it asks to run now
                    // rather than in the next batching window. RUN_AS_NON_EXPEDITED_WORK_REQUEST
                    // is the required fallback for when the app has no expedited quota left.
                    if (expedited) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            // Preserve a trailing progress request that arrives while another merge/upload runs.
            // The mutex also serializes this chain with the differently named periodic worker.
            context.workManager.enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_AUTO) || context.workManager.isRunning(TAG_MANUAL)
        }
    }
}

/**
 * A [Uri] the backup code can open, straight from the file.
 *
 * Not `getUriCompat`: that hands back a FileProvider `content://` URI, and routing the app's own
 * private cache through its own provider buys nothing here while adding a way to fail.
 */
private fun File.toUniFileUri(): Uri = UniFile.fromFile(this)!!.uri
