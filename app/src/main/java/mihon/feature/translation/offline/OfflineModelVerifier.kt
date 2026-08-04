package mihon.feature.translation.offline

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Size + SHA-256 gate for the downloaded GGUF.
 *
 * Mirrors the hard lesson from [mihon.feature.translation.detect.BubbleDetector]: an incomplete
 * or corrupt binary that still "exists" silently kills the whole feature. Both checks must pass.
 */
object OfflineModelVerifier {

    data class Result(
        val ok: Boolean,
        val sizeBytes: Long,
        val sha256: String?,
        val reason: String? = null,
    )

    fun verifyFile(
        file: File,
        expectedSize: Long = OfflineModelSpec.EXPECTED_SIZE_BYTES,
        expectedSha256: String = OfflineModelSpec.EXPECTED_SHA256,
    ): Result {
        if (!file.isFile) {
            return Result(false, 0L, null, "missing")
        }
        val size = file.length()
        if (size != expectedSize) {
            return Result(false, size, null, "size_mismatch:$size!=$expectedSize")
        }
        val digest = sha256Of(file.inputStream())
        if (!digest.equals(expectedSha256, ignoreCase = true)) {
            return Result(false, size, digest, "sha256_mismatch")
        }
        return Result(true, size, digest)
    }

    fun sha256Of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { b -> "%02X".format(b) }
    }

    fun isValidReadyFile(file: File): Boolean = verifyFile(file).ok
}
