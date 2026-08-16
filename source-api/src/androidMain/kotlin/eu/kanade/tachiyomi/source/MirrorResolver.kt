package eu.kanade.tachiyomi.source.builtin

import android.content.SharedPreferences
import androidx.core.content.edit
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
 * - when every known host is unreachable it *guesses*, via [guesses], because a redirect only
 *   teaches the app anything if something is left to follow it from. A site whose whole shipped
 *   list has been blocked at once — the normal end state for these domains — leaves nothing;
 * - it remembers the answer, so the probe runs once every [TTL_MILLIS] rather than per request.
 *
 * A user-entered override always wins — someone who knows the new domain should not have to
 * wait for a probe, and a site can move somewhere that the old host never points at.
 */
class MirrorResolver(
    private val preferences: SharedPreferences,
    private val candidates: List<String>,
    /**
     * Hosts to try, best-first, only after every known candidate has failed. Receives the
     * currently resolved host so a guess can be made relative to where the site is *now*
     * rather than to where it was when this shipped.
     */
    private val guesses: (String?) -> List<String> = { emptyList() },
    /**
     * A string that must appear in a guessed host's homepage for it to be believed.
     *
     * Only guesses are held to this. A known candidate answering at all is evidence enough —
     * it is in the list because it was the site. A guessed domain has no such history, and an
     * expired one that a squatter has picked up answers `200` just as readily as the real site,
     * so "something responded" cannot be the test. Sources without a usable marker pass none,
     * and simply do not guess.
     */
    private val guessMarker: String? = null,
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
     * Wraps [client] so that a transport failure which looks like the domain, not the network,
     * being the problem forgets the TTL and re-probes.
     *
     * This is the half that was missing: without it a site that moved stayed broken for the
     * whole six hours, because nothing ever told the resolver its answer had gone stale. A
     * false positive here is close to free — [invalidate] only clears a timestamp, so the cost
     * is one probe that re-confirms the host already in use.
     */
    fun install(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                if (e.looksLikeDeadDomain()) invalidate()
                throw e
            }
        }
        .build()

    /**
     * Forgets the resolved host so the next read re-probes.
     *
     * Called from [install] when a request fails in a way that suggests the domain, not the
     * network, is the problem — otherwise a site that moved would stay broken until the TTL
     * expired.
     */
    fun invalidate() {
        preferences.edit { remove(KEY_CHECKED_AT) }
    }

    /**
     * Whether the cached answer is old enough to re-probe.
     *
     * A probe that found nothing expires in minutes rather than hours. The long TTL exists to
     * avoid re-checking a host that works; applying it to a failure had the opposite effect,
     * pinning the app to a domain already known to be unreachable for the rest of the day.
     */
    private fun isStale(): Boolean {
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, 0L)
        val ttl = if (preferences.getBoolean(KEY_LAST_PROBE_OK, false)) TTL_MILLIS else FAILURE_TTL_MILLIS
        return System.currentTimeMillis() - checkedAt > ttl
    }

    private fun refreshInBackground() {
        scope.launch {
            if (!probeLock.tryLock()) return@launch
            try {
                if (!isStale()) return@launch
                val landed = probe()
                preferences.edit {
                    putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    putBoolean(KEY_LAST_PROBE_OK, landed != null)
                    if (landed != null) putString(KEY_RESOLVED, landed)
                }
            } finally {
                probeLock.unlock()
            }
        }
    }

    /**
     * The host the site is reachable at now, or null if nothing answered.
     *
     * Two stages, because the two kinds of candidate deserve different treatment. Known hosts
     * go first, sequentially: the list is written best-first, and a candidate that works should
     * win over one that merely also works. Guesses go second and in parallel — there is no
     * meaningful "best" among them, and walking a dozen dead hosts at [PROBE_TIMEOUT_SECONDS]
     * each would take minutes. [awaitAll] preserves order, so priority still decides the winner.
     */
    private suspend fun probe(): String? {
        val client = network.client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val resolved = preferences.getString(KEY_RESOLVED, null)

        // Start from what already worked, so a healthy domain is confirmed in one request.
        val known = buildList {
            resolved?.let(::add)
            candidates.forEach { if (it !in this) add(it) }
        }
        known.forEach { host -> reach(client, host)?.let { return it } }

        // Everything known is gone. Guessing is the only way left to find where the site went.
        if (guessMarker == null) return null
        val guessed = guesses(resolved).filterNot { it in known }
        if (guessed.isEmpty()) return null
        return coroutineScope {
            guessed.map { host -> async { reach(client, host, verifyWith = guessMarker) } }.awaitAll()
        }.firstOrNull { it != null }
    }

    /**
     * Where [host] lands, or null if it did not answer.
     *
     * Any HTTP response proves a known host is the site and is up. Insisting on 2xx would reject
     * a domain sitting behind a Cloudflare challenge — which is exactly what these sites do —
     * and a 404 on `/` still means a live server. Only a transport failure means it is gone.
     * A guessed host has to clear the higher bar of [verifyWith] as well; see [guessMarker].
     */
    private fun reach(client: OkHttpClient, host: String, verifyWith: String? = null): String? = runCatching {
        client.newCall(GET("https://$host/", PROBE_HEADERS)).execute().use { response ->
            if (verifyWith != null && !response.body.string().contains(verifyWith, ignoreCase = true)) {
                return@use null
            }
            response.request.url.host
        }
    }.getOrNull()

    private fun String.withScheme(): String =
        if (startsWith("http://") || startsWith("https://")) trimEnd('/') else "https://${trimEnd('/')}"

    companion object {
        private const val KEY_RESOLVED = "resolved_domain"
        private const val KEY_CHECKED_AT = "resolved_domain_checked_at"
        private const val KEY_LAST_PROBE_OK = "resolved_domain_last_probe_ok"

        private val TTL_MILLIS = 6.hours.inWholeMilliseconds
        private val FAILURE_TTL_MILLIS = 5.minutes.inWholeMilliseconds
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

/**
 * True when a failure looks like the domain is gone rather than the network being down.
 *
 * The reset cases matter as much as the DNS ones. A Vietnamese ISP blocking one of these sites
 * does not fail to resolve it — the name resolves to Cloudflare as usual and the TLS handshake
 * is then torn down mid-flight, surfacing as a plain "Connection reset". Treating only DNS and
 * refused connections as domain trouble missed the single most common way these hosts die.
 */
fun Throwable.looksLikeDeadDomain(): Boolean {
    val message = message.orEmpty()
    return this is java.net.UnknownHostException ||
        this is javax.net.ssl.SSLException ||
        this is java.net.ConnectException ||
        message.contains("Unable to resolve host", ignoreCase = true) ||
        message.contains("Connection reset", ignoreCase = true)
}

/**
 * Hosts continuing a numbered domain's counter, newest first.
 *
 * These sites do not move to an unrelated name when they are blocked; they increment, and they
 * register the next several in advance. [template] is a format string with one integer slot
 * (e.g. `"animehay%02d.site"`), and this walks it [ahead] steps past [highest] — which is how
 * the app can land on a domain that did not exist on the day it shipped.
 */
fun numberedHosts(template: String, highest: Int, ahead: Int): List<String> =
    (highest + ahead downTo highest + 1).map { template.format(it) }

/** Normalises a pasted override into a bare host, so "https://x.com/" and "x.com" agree. */
fun String.toHostOrNull(): String? =
    (if (startsWith("http")) this else "https://$this").toHttpUrlOrNull()?.host
