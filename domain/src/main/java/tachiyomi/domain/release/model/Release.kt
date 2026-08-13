package tachiyomi.domain.release.model

/**
 * Contains information about the latest release.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    val downloadLink: String,
    /** Monotonic Android package version. Preferred over parsing a display version when available. */
    val versionCode: Long? = null,
    /** Lowercase SHA-256 supplied by the update feed and verified before installation. */
    val sha256: String? = null,
    /** Expected APK length, used as an additional corruption check when supplied. */
    val size: Long? = null,
)
