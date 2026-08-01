package eu.kanade.tachiyomi.source.builtin

import android.content.SharedPreferences
import androidx.core.content.edit
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

/**
 * Keeps a built-in source pointed at a domain that actually answers.
 *
 * These sites move host regularly and get blocked per-ISP, which is why every one of them
 * shipped with a "paste the new domain here" preference. This does that work instead:
 *
 * - it probes the known hosts and keeps the first that responds, which handles a mirror being
 *   blocked or dead;
 * - it follows redirects and keeps the host it *lands* on, which is how a site that moves and
 *   redirects the old domain teaches the app its new one without an update;
 * - it remembers the answer, so the probe runs once every [TTL] rather than per request.
 *
 * A user-entered override always wins — someone who knows the new domain should not have to
 * wait for a probe, and a site can move somewhere that the old host never points at.
 */
class MirrorResolver(
    private val preferences: SharedPreferences,
    private val candidates: List<String>,
) {

    private val network: NetworkHelper by lazy { Injekt.get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeLock = Mutex()

    /**
     * The base URL to use right now.
     *
     * Synchronous by necessity — sources read `baseUrl` while building a request — so this
     * returns what is known and refreshes in the background when that knowledge is stale. The
     * first call after an install uses the first candidate, which is the same behaviour as
     * before; every call after a successful probe uses whatever answered.
     */
    fun baseUrl(override: String?): String {
        val manual = override?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (manual != null) return manual.withScheme()

        val resolved = preferences.getString(KEY_RESOLVED, null)
        if (isStale()) refreshInBackground()
        return (resolved ?: candidates.firstOrNull().orEmpty()).withScheme()
    }

    /**
     * Forgets the resolved host so the next read re-probes.
     *
     * Called when a request fails in a way that suggests the domain, not the network, is the
     * problem — otherwise a site that moved would stay broken until the TTL expired.
     */
    fun invalidate() {
        preferences.edit { remove(KEY_CHECKED_AT) }
    }

    private fun isStale(): Boolean {
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, 0L)
        return System.currentTimeMillis() - checkedAt > TTL_MILLIS
    }

    private fun refreshInBackground() {
        scope.launch {
            if (!probeLock.tryLock()) return@launch
            try {
                if (!isStale()) return@launch
                val landed = probe()
                preferences.edit {
                    putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    if (landed != null) putString(KEY_RESOLVED, landed)
                }
            } finally {
                probeLock.unlock()
            }
        }
    }

    /**
     * The host of the first candidate that answers, after redirects.
     *
     * Ordered rather than parallel: the list is written best-first, and a candidate that works
     * should win over one that merely also works. The client follows redirects already, so the
     * response's own URL is where the site actually lives now.
     */
    private fun probe(): String? {
        val client = network.client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        // Start from what already worked, so a healthy domain is confirmed in one request.
        val order = buildList {
            preferences.getString(KEY_RESOLVED, null)?.let(::add)
            candidates.forEach { if (it !in this) add(it) }
        }

        order.forEach { host ->
            val landed = runCatching {
                client.newCall(GET("https://$host/", PROBE_HEADERS)).execute().use { response ->
                    // Any HTTP response proves the host is the site and is up. Insisting on 2xx
                    // would reject a domain sitting behind a Cloudflare challenge — which is
                    // exactly what these sites do — and a 404 on `/` still means a live server.
                    // Only a transport failure (no response at all) means the domain is gone.
                    response.request.url.host
                }
            }.getOrNull()
            if (landed != null) return landed
        }
        return null
    }

    private fun String.withScheme(): String =
        if (startsWith("http://") || startsWith("https://")) trimEnd('/') else "https://${trimEnd('/')}"

    companion object {
        private const val KEY_RESOLVED = "resolved_domain"
        private const val KEY_CHECKED_AT = "resolved_domain_checked_at"

        private val TTL_MILLIS = 6.hours.inWholeMilliseconds
        private const val PROBE_TIMEOUT_SECONDS = 8L

        private val PROBE_HEADERS = Headers.Builder()
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .build()
    }
}

/** True when a failure looks like the domain is gone rather than the network being down. */
fun Throwable.looksLikeDeadDomain(): Boolean {
    val message = message.orEmpty()
    return this is java.net.UnknownHostException ||
        this is javax.net.ssl.SSLHandshakeException ||
        this is java.net.ConnectException ||
        message.contains("Unable to resolve host", ignoreCase = true)
}

/** Normalises a pasted override into a bare host, so "https://x.com/" and "x.com" agree. */
fun String.toHostOrNull(): String? =
    (if (startsWith("http")) this else "https://$this").toHttpUrlOrNull()?.host
