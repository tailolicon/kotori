package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()

    override suspend fun doWork(): Result {
        val url = inputData.getString(EXTRA_DOWNLOAD_URL)
        val title = inputData.getString(EXTRA_DOWNLOAD_TITLE) ?: context.stringResource(MR.strings.app_name)
        val expectedSha256 = inputData.getString(EXTRA_DOWNLOAD_SHA256)
        val expectedSize = inputData.getLong(EXTRA_DOWNLOAD_SIZE, UNKNOWN_SIZE).takeIf { it >= 0 }

        if (url.isNullOrEmpty()) {
            return Result.failure()
        }

        setForegroundSafely()

        withIOContext {
            downloadApk(title, url, expectedSha256, expectedSize)
        }

        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_APP_UPDATER,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Called to start downloading apk of new update
     *
     * @param url url location of file
     */
    private suspend fun downloadApk(
        title: String,
        url: String,
        expectedSha256: String?,
        expectedSize: Long?,
    ) {
        // Show notification download starting.
        notifier.onDownloadStarted(title)
        AppUpdateDownloadState.state.value = AppUpdateDownloadState.State.Downloading(null)

        val progressListener = object : ProgressListener {
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last notification sent to avoid posting too many.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                val progress = (100 * (bytesRead.toFloat() / contentLength)).toInt()
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    notifier.onProgressChange(progress)
                    AppUpdateDownloadState.state.value =
                        AppUpdateDownloadState.State.Downloading(progress)
                }
            }
        }

        try {
            // Download the new update.
            val response = network.client.newCachelessCallWithProgress(GET(url), progressListener)
                .await()

            // File where the apk will be saved.
            val apkFile = File(context.externalCacheDir, "update.apk")

            if (response.isSuccessful) {
                response.body.source().saveTo(apkFile)
            } else {
                response.close()
                throw Exception("Unsuccessful response")
            }
            AppUpdateVerifier.verify(apkFile, expectedSha256, expectedSize)
            val apkUri = apkFile.getUriCompat(context)
            notifier.cancel()
            notifier.promptInstall(apkUri)
            AppUpdateDownloadState.state.value = AppUpdateDownloadState.State.Finished(apkUri)
        } catch (e: Exception) {
            val shouldCancel = e is CancellationException ||
                (e is StreamResetException && e.errorCode == ErrorCode.CANCEL)
            if (shouldCancel) {
                notifier.cancel()
                AppUpdateDownloadState.reset()
            } else {
                notifier.onDownloadError(url, title, expectedSha256, expectedSize)
                AppUpdateDownloadState.state.value = AppUpdateDownloadState.State.Error(e.message)
            }
        }
    }

    companion object {
        private const val TAG = "AppUpdateDownload"

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"
        const val EXTRA_DOWNLOAD_SHA256 = "DOWNLOAD_SHA256"
        const val EXTRA_DOWNLOAD_SIZE = "DOWNLOAD_SIZE"
        private const val UNKNOWN_SIZE = -1L

        fun start(
            context: Context,
            url: String,
            title: String? = null,
            sha256: String? = null,
            size: Long? = null,
        ) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadJob>()
                .setConstraints(constraints)
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        EXTRA_DOWNLOAD_URL to url,
                        EXTRA_DOWNLOAD_TITLE to title,
                        EXTRA_DOWNLOAD_SHA256 to sha256,
                        EXTRA_DOWNLOAD_SIZE to (size ?: UNKNOWN_SIZE),
                    ),
                )
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
