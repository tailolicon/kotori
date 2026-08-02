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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

    suspend fun upload(bytes: ByteArray, fileName: String = REMOTE_FILE_NAME) = withIOContext {
        val putOnce = {
            authenticatedRequest(remoteFileUrl(fileName))
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

    suspend fun listFileNames(): List<String> = withIOContext {
        val request = authenticatedRequest(folderUrl())
            .method("PROPFIND", EMPTY_REQUEST)
            .header("Depth", "1")
            .build()
        client.newCall(request).await().use { response ->
            // A failed listing must never break a sync that has already succeeded.
            if (!response.isSuccessful && response.code != 207) {
                return@withIOContext emptyList()
            }
            parseHrefFileNames(response.body.string())
        }
    }

    suspend fun delete(fileName: String) = withIOContext {
        val request = authenticatedRequest(remoteFileUrl(fileName))
            .delete()
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException(
                    "WebDAV delete failed: HTTP ${response.code} ${response.message}",
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

    /**
     * Servers use different XML namespace prefixes (`<D:href>`, `<d:href>`, plain `<href>`).
     * Match the local name case-insensitively and ignore the prefix so a hard-coded vendor
     * prefix does not break listing on another server.
     */
    private fun parseHrefFileNames(body: String): List<String> {
        val folderSegment = URLDecoder.decode(
            folderUrl().trimEnd('/').substringAfterLast('/'),
            StandardCharsets.UTF_8.name(),
        )
        return HREF_ELEMENT_REGEX.findAll(body).mapNotNull { match ->
            val href = match.groupValues[1].trim()
            val segment = href.trimEnd('/').substringAfterLast('/')
            if (segment.isEmpty()) return@mapNotNull null
            val decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
            if (decoded.isEmpty() || decoded == folderSegment) null else decoded
        }.toList()
    }

    private fun remoteFileUrl(fileName: String = REMOTE_FILE_NAME): String =
        "${folderUrl()}/$fileName"

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

        // (?i) + optional prefix: see parseHrefFileNames for why this is not a fixed <D:href>.
        val HREF_ELEMENT_REGEX =
            Regex("""(?i)<(?:[^:>\s]+:)?href(?:\s[^>]*)?>([^<]*)</(?:[^:>\s]+:)?href\s*>""")
    }
}
