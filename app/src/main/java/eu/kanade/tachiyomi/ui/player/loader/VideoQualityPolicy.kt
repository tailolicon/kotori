package eu.kanade.tachiyomi.ui.player.loader

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.animesource.model.Video
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.getOrNull

/**
 * Which video to open an episode with.
 *
 * The default is the best the source offers — a source hands its list back in whatever order it
 * likes, and taking the first entry was how a 480p stream could win on a device that could
 * comfortably play 1080p. Quality is only capped when the link is measurably too slow for it,
 * so a good connection is never second-guessed.
 */
object VideoQualityPolicy {

    /** Below this, a full-quality stream will stall more than it plays. */
    private const val SLOW_LINK_KBPS = 4_000

    /** What a slow link is allowed to pull. */
    private const val SLOW_LINK_MAX_HEIGHT = 720

    /**
     * The tallest video worth loading right now, or [Int.MAX_VALUE] when the link is fine or its
     * speed is unknown — an unknown estimate is not evidence of a bad connection.
     */
    fun maxHeight(context: Context? = Injekt.getOrNull<Application>()): Int {
        val capabilities = context
            ?.getSystemService<ConnectivityManager>()
            ?.let { manager -> manager.getNetworkCapabilities(manager.activeNetwork) }
            ?: return Int.MAX_VALUE

        // Wi-Fi and Ethernet report optimistic link speeds; trust the transport over the estimate.
        val onFastTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (onFastTransport) return Int.MAX_VALUE

        val downstream = capabilities.linkDownstreamBandwidthKbps
        return if (downstream in 1 until SLOW_LINK_KBPS) SLOW_LINK_MAX_HEIGHT else Int.MAX_VALUE
    }

    /** The video's vertical resolution, from the field when the source filled it in or its title. */
    fun heightOf(video: Video): Int? =
        video.resolution?.takeIf { it > 0 }
            ?: RESOLUTION_IN_TITLE.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: NAMED_RESOLUTIONS.entries.firstOrNull { (name, _) ->
                video.videoTitle.contains(name, ignoreCase = true)
            }?.value

    private val RESOLUTION_IN_TITLE = Regex("""(\d{3,4})\s*[pP]""")

    private val NAMED_RESOLUTIONS = linkedMapOf(
        "2160" to 2160,
        "4k" to 2160,
        "1440" to 1440,
        "2k" to 1440,
        "fhd" to 1080,
        "full hd" to 1080,
        "hd" to 720,
        "sd" to 480,
    )
}
