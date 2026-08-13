package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Portable update feed consumed directly by Kotori; it has no dependency on GitHub's API. */
@Serializable
data class KotoriReleaseManifest(
    val schema: Int = 1,
    val versionCode: Long,
    val versionName: String,
    val changelog: String = "",
    val releaseUrl: String = "",
    val assets: List<KotoriUpdateAsset>,
)

@Serializable
data class KotoriUpdateAsset(
    /** Android ABI (for example x86_64 or arm64-v8a), or universal as a fallback. */
    val abi: String = UNIVERSAL,
    val url: String,
    @SerialName("sha256")
    val sha256: String,
    val size: Long? = null,
) {
    companion object {
        const val UNIVERSAL = "universal"
    }
}

internal fun KotoriReleaseManifest.selectAsset(supportedAbis: List<String>): KotoriUpdateAsset? =
    supportedAbis.firstNotNullOfOrNull { abi ->
        assets.firstOrNull { it.abi.equals(abi, ignoreCase = true) }
    } ?: assets.firstOrNull { it.abi.equals(KotoriUpdateAsset.UNIVERSAL, ignoreCase = true) }
