package eu.kanade.tachiyomi.data.updater

import java.io.File
import java.security.MessageDigest

internal object AppUpdateVerifier {

    fun verify(file: File, expectedSha256: String?, expectedSize: Long?) {
        if (expectedSize != null && file.length() != expectedSize) {
            file.delete()
            throw SecurityException("Downloaded APK size does not match update manifest")
        }
        if (expectedSha256.isNullOrBlank()) return

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            file.delete()
            throw SecurityException("Downloaded APK checksum does not match update manifest")
        }
    }
}
