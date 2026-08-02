package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
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
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
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

    override suspend fun doWork(): Result {
        val isManual = inputData.getBoolean(IS_MANUAL_KEY, false)
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
            val client = WebDavSyncClient(prefs)

            // null = remote file missing (first ever sync) — skip restore and create it on upload
            val remoteBytes = client.download()
            if (remoteBytes != null) {
                downloadFile = File(context.cacheDir, DOWNLOAD_TEMP_NAME).also {
                    it.writeBytes(remoteBytes)
                }
                BackupRestorer(context, notifier, isSync = true)
                    .restore(downloadFile.getUriCompat(context), RESTORE_OPTIONS)
            }

            // Fresh backup of the now-merged local state, then push it upstream
            uploadFile = File(context.cacheDir, UPLOAD_TEMP_NAME)
            BackupCreator(context, isAutoBackup = false)
                .backup(uploadFile.getUriCompat(context), BACKUP_OPTIONS)
            val uploadBytes = uploadFile.readBytes()
            client.upload(uploadBytes)

            prefs.lastSyncTimestamp.set(System.currentTimeMillis())

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

        // privateSettings must stay false: the uploaded backup lives on the same WebDAV server
        // whose credentials are stored under private preference keys.
        private val BACKUP_OPTIONS = BackupOptions(privateSettings = false)
        private val RESTORE_OPTIONS = RestoreOptions()

        // Opening/closing the app repeatedly would otherwise enqueue a chain of full library
        // uploads; this floor keeps opportunistic syncs to at most once every five minutes.
        private const val MIN_AUTO_SYNC_INTERVAL_MS = 5 * 60 * 1000L

        private const val DOWNLOAD_TEMP_NAME = "kotori-sync-download.tachibk"
        private const val UPLOAD_TEMP_NAME = "kotori-sync-upload.tachibk"

        private const val MAX_SNAPSHOTS = 3
        private val SNAPSHOT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val SNAPSHOT_NAME_REGEX = Regex("""kotori-sync-\d{8}\.tachibk""")

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
                    .setInputData(workDataOf(IS_MANUAL_KEY to false))
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
        fun startNow(context: Context) = enqueueOnce(context, isManual = true)

        /**
         * Leaving the app — push what was just read.
         *
         * Not manual, because a phone that walks out of wifi would otherwise raise an error
         * notification every single time the app is closed.
         */
        fun startOnLeave(context: Context) {
            if (!Injekt.get<SyncPreferences>().syncEnabled.get()) return
            enqueueOnce(context, isManual = false, expedited = true)
        }

        /**
         * Progress changed while the app is open — push it now rather than at exit.
         *
         * Waiting for `onStop` is a single point of failure: a force stop, or an OEM battery
         * manager that kills the process outright, skips it, and everything read in that session
         * stays on the one device. Pushing during the session bounds that loss to the debounce
         * window instead of a whole sitting. Rate limited by [startIfDue] so a long read does not
         * become a stream of full-library uploads.
         */
        fun startOnProgress(context: Context) = startIfDue(context)

        private fun enqueueOnce(context: Context, isManual: Boolean, expedited: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncJob>()
                .addTag(if (isManual) TAG_MANUAL else TAG_AUTO)
                .setConstraints(
                    Constraints(requiredNetworkType = NetworkType.CONNECTED),
                )
                .setInputData(workDataOf(IS_MANUAL_KEY to isManual))
                .apply {
                    // The push on leaving races the process being killed, so it asks to run now
                    // rather than in the next batching window. RUN_AS_NON_EXPEDITED_WORK_REQUEST
                    // is the required fallback for when the app has no expedited quota left.
                    if (expedited) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        fun startIfDue(context: Context) {
            val prefs = Injekt.get<SyncPreferences>()
            if (!prefs.syncEnabled.get()) return

            val elapsed = System.currentTimeMillis() - prefs.lastSyncTimestamp.get()
            if (elapsed < MIN_AUTO_SYNC_INTERVAL_MS) return

            // Shares the one unique name with every other one-off sync, so an opening pull and a
            // leaving push can never queue up behind each other.
            enqueueOnce(context, isManual = false)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_AUTO) || context.workManager.isRunning(TAG_MANUAL)
        }
    }
}
