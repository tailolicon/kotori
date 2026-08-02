package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.sync.service.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class WebDavSyncClient(
    private val preferences: SyncPreferences,
) {

    private val client = Injekt.get<NetworkHelper>().client

    suspend fun download(): ByteArray? = withIOContext {
        val request = authenticatedRequest(remoteFileUrl())
            .get()
            .build()
        client.newCall(request).await().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body.bytes()
                else -> throw IOException(
                    "WebDAV download failed: HTTP ${response.code} ${response.message}",
                )
            }
        }
    }

    suspend fun upload(bytes: ByteArray) = withIOContext {
        val putOnce = {
            authenticatedRequest(remoteFileUrl())
                .put(bytes.toRequestBody(OCTET_STREAM))
                .build()
        }

        client.newCall(putOnce()).await().use { response ->
            if (response.isSuccessful) return@withIOContext
            // WebDAV 409 means the parent collection does not exist; MKCOL creates it, then retry PUT once.
            if (response.code != 409) {
                throw IOException(
                    "WebDAV upload failed: HTTP ${response.code} ${response.message}",
                )
            }
        }

        client.newCall(
            authenticatedRequest(folderUrl())
                .method("MKCOL", null)
                .build(),
        ).await().close()

        client.newCall(putOnce()).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "WebDAV upload failed: HTTP ${response.code} ${response.message}",
                )
            }
        }
    }

    suspend fun testConnection(): Result<Unit> = withIOContext {
        try {
            val request = authenticatedRequest(folderUrl())
                .method("PROPFIND", EMPTY_REQUEST)
                .header("Depth", "0")
                .build()
            client.newCall(request).await().use { response ->
                when {
                    response.isSuccessful || response.code == 207 -> Result.success(Unit)
                    response.code == 401 || response.code == 403 -> {
                        Result.failure(
                            IOException("Sai tên đăng nhập hoặc mật khẩu WebDAV."),
                        )
                    }
                    else -> {
                        Result.failure(
                            IOException("Kết nối WebDAV thất bại (mã ${response.code})."),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun remoteFileUrl(): String = "${folderUrl()}/$REMOTE_FILE_NAME"

    private fun folderUrl(): String {
        val url = preferences.syncUrl.get().trim().trimEnd('/')
        if (url.isBlank()) {
            throw IllegalStateException("Vui lòng cấu hình URL WebDAV trước khi đồng bộ.")
        }
        return url
    }

    private fun authenticatedRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        val username = preferences.syncUsername.get()
        val password = preferences.syncPassword.get()
        if (username.isNotBlank() || password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        return builder
    }

    private companion object {
        const val REMOTE_FILE_NAME = "kotori-sync.tachibk"
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val EMPTY_REQUEST = ByteArray(0).toRequestBody(null)
    }
}
