package tachiyomi.data.release

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class KotoriReleaseManifestTest {

    @Test
    fun `manifest parses and chooses first supported ABI`() {
        val manifest = Json.decodeFromString<KotoriReleaseManifest>(
            """
            {
              "schema": 1,
              "versionCode": 123,
              "versionName": "1.0.5-123",
              "changelog": "Fixed translation",
              "assets": [
                {"abi":"universal","url":"universal.apk","sha256":"aa","size":20},
                {"abi":"arm64-v8a","url":"arm64.apk","sha256":"bb","size":10},
                {"abi":"x86_64","url":"x64.apk","sha256":"cc","size":11}
              ]
            }
            """.trimIndent(),
        )

        manifest.versionCode shouldBe 123
        manifest.selectAsset(listOf("x86_64", "x86"))?.url shouldBe "x64.apk"
        manifest.selectAsset(listOf("arm64-v8a", "armeabi-v7a"))?.url shouldBe "arm64.apk"
    }

    @Test
    fun `manifest falls back to universal APK`() {
        val manifest = KotoriReleaseManifest(
            versionCode = 123,
            versionName = "1.0.5-123",
            assets = listOf(
                KotoriUpdateAsset("universal", "universal.apk", "aa"),
                KotoriUpdateAsset("x86_64", "x64.apk", "bb"),
            ),
        )

        manifest.selectAsset(listOf("riscv64"))?.url shouldBe "universal.apk"
    }
}
