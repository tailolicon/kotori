package eu.kanade.tachiyomi.data.updater

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AppUpdateVerifierTest {

    @Test
    fun `accepts APK with matching size and checksum`() {
        val file = Files.createTempFile("kotori-update", ".apk").toFile()
        file.writeText("verified update")

        AppUpdateVerifier.verify(
            file = file,
            expectedSha256 = "59f19f34399b14e5f1628642e9ce341d660094ba76898e4db6b1875f525b6a6a",
            expectedSize = 15,
        )

        file.exists().shouldBeTrue()
        file.delete()
    }

    @Test
    fun `rejects and deletes APK with wrong checksum`() {
        val file = Files.createTempFile("kotori-update", ".apk").toFile()
        file.writeText("tampered update")

        shouldThrow<SecurityException> {
            AppUpdateVerifier.verify(file, "00".repeat(32), file.length())
        }

        file.exists().shouldBeFalse()
    }

    @Test
    fun `rejects and deletes APK with wrong size`() {
        val file = Files.createTempFile("kotori-update", ".apk").toFile()
        file.writeText("truncated")

        shouldThrow<SecurityException> {
            AppUpdateVerifier.verify(file, null, file.length() + 1)
        }

        file.exists().shouldBeFalse()
    }
}
