package mihon.feature.translation.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class OfflineModelVerifierTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `missing file is not valid`() {
        val file = File(tempDir, "missing.gguf")
        val result = OfflineModelVerifier.verifyFile(file, expectedSize = 10L, expectedSha256 = "AA")
        assertFalse(result.ok)
        assertEquals("missing", result.reason)
    }

    @Test
    fun `size mismatch fails before hash`() {
        val file = File(tempDir, "partial.gguf")
        file.writeBytes(ByteArray(8) { 1 })
        val result = OfflineModelVerifier.verifyFile(
            file,
            expectedSize = 16L,
            expectedSha256 = "00",
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.startsWith("size_mismatch"))
        assertEquals(8L, result.sizeBytes)
    }

    @Test
    fun `matching size and sha256 passes`() {
        val payload = "kotori-offline-test-bytes".toByteArray()
        val file = File(tempDir, "ok.gguf")
        file.writeBytes(payload)
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02X".format(it) }

        val result = OfflineModelVerifier.verifyFile(
            file,
            expectedSize = payload.size.toLong(),
            expectedSha256 = sha,
        )
        assertTrue(result.ok)
        assertEquals(sha, result.sha256)
    }

    @Test
    fun `wrong sha256 fails even when size matches`() {
        val payload = ByteArray(32) { it.toByte() }
        val file = File(tempDir, "badhash.gguf")
        file.writeBytes(payload)
        val result = OfflineModelVerifier.verifyFile(
            file,
            expectedSize = 32L,
            expectedSha256 = "0".repeat(64),
        )
        assertFalse(result.ok)
        assertEquals("sha256_mismatch", result.reason)
    }

    @Test
    fun `spec constants match handoff`() {
        assertEquals(1_133_080_512L, OfflineModelSpec.EXPECTED_SIZE_BYTES)
        assertEquals(
            "4383AC0C3C8E476DE98FF979C2A3F069F8C4FB385E7860CF2D28DA896CC477C7",
            OfflineModelSpec.EXPECTED_SHA256,
        )
        assertEquals("HY-MT1.5-1.8B-Q4_K_M", OfflineModelSpec.IDENTITY)
    }
}
