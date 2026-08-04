package mihon.feature.translation.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * App-private storage + user-initiated download for HY-MT GGUF.
 *
 * Resume validates HTTP 206 Content-Range; malformed/wrong-offset ranges never write a
 * ranged body onto a truncated file — the response is closed and a single full GET is issued.
 */
class OfflineModelStore(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
    initialReady: Boolean = false,
    private val onReadyChanged: (Boolean) -> Unit = {},
) {
    private val rootDir: File = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    private val modelFile: File = File(rootDir, OfflineModelSpec.FILE_NAME)
    private val partialFile: File = File(rootDir, "${OfflineModelSpec.FILE_NAME}.partial")

    private val downloadMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)

    // A successful download is hash-verified once, then remembered by the app preference across
    // restarts. Re-hashing a 1.1 GB app-private file every time the settings screen opens or a page
    // starts would add seconds of avoidable I/O; exact size still guards stale/deleted files.
    @Volatile
    private var verifiedReady = initialReady && modelFile.hasExpectedSize()

    private val _state = MutableStateFlow(inspect())
    val state: StateFlow<OfflineModelState> = _state.asStateFlow()

    fun modelPathOrNull(): String? = modelFile.takeIf { hasValidModel() }?.absolutePath

    fun hasValidModel(): Boolean = verifiedReady && modelFile.hasExpectedSize()

    fun refresh() {
        publish(inspect())
    }

    suspend fun download(
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): OfflineModelState = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            cancelRequested.set(false)
            try {
                if (hasValidModel()) {
                    return@withLock publish(OfflineModelState.Ready(modelFile.length()))
                }
                verifiedReady = false
                if (modelFile.exists()) {
                    modelFile.delete()
                }

                publish(
                    OfflineModelState.Downloading(
                        bytesDownloaded = partialFile.takeIf { it.isFile }?.length() ?: 0L,
                        totalBytes = OfflineModelSpec.EXPECTED_SIZE_BYTES,
                    ),
                )

                fetchToPartial(onProgress)

                if (cancelRequested.get()) {
                    return@withLock publish(OfflineModelState.Idle(partialBytes = partialFile.lengthOrZero()))
                }

                publish(OfflineModelState.Verifying(partialFile.length()))
                val verified = OfflineModelVerifier.verifyFile(partialFile)
                if (!verified.ok) {
                    partialFile.delete()
                    return@withLock publish(
                        OfflineModelState.Failed(
                            messageKey = "verify_failed",
                            detail = verified.reason,
                            canRetry = true,
                        ),
                    )
                }

                if (!installAtomic(partialFile, modelFile) || !modelFile.hasExpectedSize()) {
                    modelFile.delete()
                    return@withLock publish(
                        OfflineModelState.Failed(
                            messageKey = "install_failed",
                            canRetry = true,
                        ),
                    )
                }

                verifiedReady = true
                publish(OfflineModelState.Ready(modelFile.length()))
            } catch (e: Throwable) {
                activeCall.getAndSet(null)?.cancel()
                if (cancelRequested.get() || e is DownloadCancelled) {
                    publish(OfflineModelState.Idle(partialBytes = partialFile.lengthOrZero()))
                } else {
                    logcat { "Offline model download failed: ${e.message}" }
                    publish(
                        OfflineModelState.Failed(
                            messageKey = "download_failed",
                            detail = e.message,
                            canRetry = true,
                        ),
                    )
                }
            } finally {
                activeCall.getAndSet(null)
            }
        }
    }

    fun cancelDownload() {
        cancelRequested.set(true)
        activeCall.get()?.cancel()
    }

    /**
     * Deletes model + partial. Caller must [mihon.feature.translation.provider.TranslationProviders.releaseOffline]
     * first so inference cannot mmap a deleted file.
     */
    suspend fun delete(): OfflineModelState = withContext(Dispatchers.IO) {
        cancelDownload()
        downloadMutex.withLock {
            verifiedReady = false
            modelFile.delete()
            partialFile.delete()
            publish(OfflineModelState.Missing)
        }
    }

    private fun installAtomic(from: File, to: File): Boolean {
        require(from.parentFile?.absolutePath == to.parentFile?.absolutePath) {
            "installAtomic requires same directory"
        }
        to.delete()
        return from.renameTo(to)
    }

    private fun fetchToPartial(onProgress: (Long, Long) -> Unit) {
        val expected = OfflineModelSpec.EXPECTED_SIZE_BYTES
        var existing = partialFile.takeIf { it.isFile }?.length() ?: 0L
        if (existing > expected) {
            partialFile.delete()
            existing = 0L
        }
        if (existing == expected) return

        // At most one full-GET retry when a Range resume is unusable.
        var allowRange = existing > 0L
        var fullGetRetried = false

        while (true) {
            if (cancelRequested.get()) throw DownloadCancelled()
            val request = Request.Builder()
                .url(OfflineModelSpec.DOWNLOAD_URL)
                .header("User-Agent", USER_AGENT)
                .apply {
                    if (allowRange && existing > 0L) header("Range", "bytes=$existing-")
                }
                .build()

            val call = httpClient.newCall(request)
            activeCall.set(call)
            val response = call.execute()
            try {
                when (response.code) {
                    416 -> return // leave partial for verification
                    200, 206 -> Unit
                    else -> throw IOException("HTTP ${response.code}")
                }

                val plan = OfflineHttpRange.plan(
                    existing = existing,
                    httpCode = response.code,
                    contentRangeHeader = response.header("Content-Range"),
                )

                when (plan) {
                    OfflineHttpRange.ResumePlan.RetryFullGet -> {
                        // Close ranged body WITHOUT writing, then one bounded full GET.
                        response.close()
                        if (fullGetRetried) {
                            throw IOException("resume_range_unusable")
                        }
                        fullGetRetried = true
                        allowRange = false
                        partialFile.delete()
                        existing = 0L
                        continue
                    }
                    OfflineHttpRange.ResumePlan.WriteFromStart -> {
                        if (existing > 0L) {
                            partialFile.delete()
                            existing = 0L
                        }
                        writeBody(response, append = false, startAt = 0L, onProgress = onProgress)
                        return
                    }
                    is OfflineHttpRange.ResumePlan.Append -> {
                        writeBody(
                            response,
                            append = true,
                            startAt = plan.fileOffset,
                            onProgress = onProgress,
                            totalHint = plan.total,
                        )
                        return
                    }
                }
            } catch (e: Throwable) {
                response.close()
                throw e
            }
        }
    }

    private fun writeBody(
        response: Response,
        append: Boolean,
        startAt: Long,
        onProgress: (Long, Long) -> Unit,
        totalHint: Long? = null,
    ) {
        response.use { resp ->
            val body = resp.body
            val expected = OfflineModelSpec.EXPECTED_SIZE_BYTES
            val total = totalHint
                ?: body.contentLength().takeIf { it > 0 }?.let { if (append) startAt + it else it }
                ?: expected

            RandomAccessFile(partialFile, "rw").use { raf ->
                if (append) raf.seek(startAt) else raf.setLength(0)
                val channel = raf.channel
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = startAt
                    while (true) {
                        if (cancelRequested.get()) throw DownloadCancelled()
                        val read = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            if (cancelRequested.get()) throw DownloadCancelled()
                            throw e
                        }
                        if (read < 0) break
                        if (read == 0) continue
                        raf.write(buffer, 0, read)
                        written += read
                        if (written % (512 * 1024) < read) {
                            channel.force(false)
                        }
                        onProgress(written, total)
                        publish(OfflineModelState.Downloading(written, total))
                    }
                    channel.force(true)
                }
            }
        }
    }

    private fun inspect(): OfflineModelState {
        if (hasValidModel()) {
            return OfflineModelState.Ready(modelFile.length())
        }
        val partial = partialFile.lengthOrZero()
        return if (partial > 0L) {
            OfflineModelState.Idle(partialBytes = partial)
        } else {
            OfflineModelState.Missing
        }
    }

    private fun publish(state: OfflineModelState): OfflineModelState {
        _state.value = state
        onReadyChanged(state is OfflineModelState.Ready)
        return state
    }

    private fun File.lengthOrZero(): Long = if (isFile) length() else 0L

    private fun File.hasExpectedSize(): Boolean = isFile && length() == OfflineModelSpec.EXPECTED_SIZE_BYTES

    private class DownloadCancelled : IOException("cancelled")

    companion object {
        const val DIR_NAME = "offline_translation"
        private const val USER_AGENT = "Kotori-OfflineTranslation/1.0 (Android)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

sealed class OfflineModelState {
    data object Missing : OfflineModelState()
    data class Idle(val partialBytes: Long = 0L) : OfflineModelState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : OfflineModelState()
    data class Verifying(val bytes: Long) : OfflineModelState()
    data class Ready(val bytes: Long) : OfflineModelState()
    data class Failed(
        val messageKey: String,
        val detail: String? = null,
        val canRetry: Boolean,
    ) : OfflineModelState()

    val isReady: Boolean get() = this is Ready
    val isBusy: Boolean get() = this is Downloading || this is Verifying
}
