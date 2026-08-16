package app.kotori.extension.vi.animevietsub

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lets the player's own page produce the stream URL, and watches for it going out.
 *
 * AnimeVietsub's embed builds its playlist in obfuscated JavaScript — there is no `.m3u8` anywhere
 * in the 108 KB of HTML to read out, and the names in it are regenerated often enough that
 * reimplementing that arithmetic in Kotlin would break on their schedule rather than ours. Loading the page in
 * a WebView and taking the URL it *asks for* costs one page load and keeps working across their
 * rewrites, because it depends on the request the player has to make, not on how it was written.
 *
 * The WebView is created, used and destroyed on the main thread, which is the only thread it may
 * touch, and it is always torn down — on a hit, on a timeout, or if the caller is cancelled.
 */
internal class WebViewResolver(private val context: Application) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        url: String,
        referer: String,
        userAgent: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        matches: (String) -> Boolean,
    ): String? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null

            val destroy = {
                handler.post {
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                }
                Unit
            }

            handler.post {
                val view = WebView(context)
                webView = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    // Without this the player loads, sits there, and never asks for a stream:
                    // WebView refuses to start media until someone taps, and nobody is going to —
                    // this page is off-screen and exists only to name the playlist.
                    mediaPlaybackRequiresUserGesture = false
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val requested = request.url.toString()
                        if (matches(requested) && continuation.isActive) {
                            continuation.resume(requested)
                            destroy()
                        }
                        return adStubOrNull(request.url.host.orEmpty())
                    }
                }
                view.loadUrl(url, mapOf("Referer" to referer))
            }

            continuation.invokeOnCancellation { destroy() }
        }
    }

    /**
     * A do-nothing script for the ad hosts, so the player stops refusing to start.
     *
     * The page checks that its advertising scripts loaded and, when they did not, navigates to
     * `/adblock-warning` instead of playing the episode. On a connection that filters ads at the
     * DNS level they never can: measured from the reader's own network, both
     * `pagead2.googlesyndication.com` and `securepubads.g.doubleclick.net` fail to resolve. That is
     * a property of the network, not a choice this player made, and no advertisement was ever going
     * to reach a native video screen. Answering with the globals the check looks for lets the page
     * get on with loading the episode.
     */
    private fun adStubOrNull(host: String): WebResourceResponse? {
        if (AD_HOSTS.none { host == it || host.endsWith(".$it") }) return null
        return WebResourceResponse("application/javascript", "utf-8", AD_STUB.byteInputStream())
    }

    companion object {
        // Long enough for a page that has to boot a player, short enough that an embed which
        // never names a playlist — the current AnimeVsub one assembles its stream in JavaScript and
        // feeds it to the video element directly — fails while the reader is still watching the
        // spinner rather than after they have given up.
        private const val DEFAULT_TIMEOUT_MILLIS = 8_000L

        private val AD_HOSTS = listOf(
            "googlesyndication.com",
            "doubleclick.net",
            "googletagservices.com",
            "adservice.google.com",
        )

        private val AD_STUB =
            """
            window.adsbygoogle = window.adsbygoogle || [];
            window.adsbygoogle.loaded = true;
            window.googletag = window.googletag || { cmd: [] };
            window.googletag.apiReady = true;
            """.trimIndent()
    }
}
