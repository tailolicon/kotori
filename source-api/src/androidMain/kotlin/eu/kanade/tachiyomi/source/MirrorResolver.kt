package eu.kanade.tachiyomi.source

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * - it reads a [feed][FEED_URL] of hosts known to be alive, refreshed by CI every few hours, so
 *   a move is followed by fetching a file rather than by shipping a release. This is the only
 *   part that keeps working when a site jumps somewhere unguessable — the list in the apk is
 *   frozen the day it is built, and this is not;
 * - it probes the known hosts and keeps the first that responds, which handles a mirror being
 *   blocked or dead;
 * - it follows redirects and keeps the host it *lands* on, which is how a site that moves and
 *   redirects the old domain teaches the app its new one without an update;
 * - it follows a site's own published "where we are now" link, when the feed names one, because
 *   a site that announces its moves is telling the truth about them and no amount of probing
 *   beats being told;
 * - when every known host is unreachable it *guesses*, via [guesses], because a redirect only
 *   teaches the app anything if something is left to follow it from. A site whose whole shipped
 *   list has been blocked at once — the normal end state for these domains — leaves nothing;
 * - it remembers the answer, so the probe runs once every [TTL_MILLIS] rather than per request.
 *
 * A user-entered override always wins — someone who knows the new domain should not have to
 * wait for a probe, and a site can move somewhere that the old host never points at.
 *
 * The constructor signature is load-bearing: extensions compile against this but ship without
 * it, so an already-installed apk calls whatever shape it was built against. Anything new here
 * therefore arrives through the feed, as data, rather than as another parameter.
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
     * Three stages, in descending order of how much the answer can be trusted. Known hosts go
     * first, sequentially: the feed and the shipped list are both written best-first, and a
     * candidate that works should win over one that merely also works. An announced host goes
     * next — it is authoritative, but only worth the round trip once everything already known
     * has failed. Guesses go last and in parallel: there is no meaningful "best" among them,
     * and walking a dozen dead hosts at [PROBE_TIMEOUT_SECONDS] each would take minutes.
     * [awaitAll] preserves order, so priority still decides the winner.
     */
    private suspend fun probe(): String? {
        val client = network.client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val resolved = preferences.getString(KEY_RESOLVED, null)
        val feed = parseMirrorFeed(feedBody(client).orEmpty(), candidates)

        // Start from what already worked, then what CI last saw working, then the shipped list.
        val known = buildList {
            resolved?.let(::add)
            feed?.domains?.forEach { if (it !in this) add(it) }
            candidates.forEach { if (it !in this) add(it) }
        }
        known.forEach { host -> reach(client, host)?.let { return it } }

        // Everything known is gone. A marker is required from here on: nothing below is a host
        // the site was ever observed at, so "it answered" stops being evidence. See [guessMarker].
        val marker = feed?.marker ?: guessMarker ?: return null

        feed?.announce?.forEach { url ->
            reach(client, url, verifyWith = marker)?.let { return it }
        }

        if (guessMarker == null) return null
        val guessed = guesses(resolved).filterNot { it in known }
        if (guessed.isEmpty()) return null
        return coroutineScope {
            guessed.map { host -> async { reach(client, host, verifyWith = guessMarker) } }.awaitAll()
        }.firstOrNull { it != null }
    }

    /**
     * The raw domain feed, from cache when it is fresh and from the network otherwise.
     *
     * A failed fetch falls back to the last copy however old it is, rather than to nothing: a
     * stale list of hosts is strictly better than the frozen one in the apk, and the case where
     * this fetch fails is exactly the case where the network is having a bad day anyway.
     */
    private fun feedBody(client: OkHttpClient): String? {
        val cached = preferences.getString(KEY_FEED, null)
        val age = System.currentTimeMillis() - preferences.getLong(KEY_FEED_AT, 0L)
        if (cached != null && age < FEED_TTL_MILLIS) return cached

        val fresh = runCatching {
            client.newCall(GET(FEED_URL, PROBE_HEADERS)).execute().use { response ->
                response.body.string().takeIf { response.isSuccessful && it.isNotBlank() }
            }
        }.getOrNull()

        if (fresh != null) {
            preferences.edit {
                putString(KEY_FEED, fresh)
                putLong(KEY_FEED_AT, System.currentTimeMillis())
            }
        }
        return fresh ?: cached
    }

    /**
     * Where [target] lands, or null if it did not answer.
     *
     * Takes a bare host or a full URL, because an announced location is a link rather than a
     * domain — the point of a short link is that it is stable while what it points at is not.
     *
     * Any HTTP response proves a known host is the site and is up. Insisting on 2xx would reject
     * a domain sitting behind a Cloudflare challenge — which is exactly what these sites do —
     * and a 404 on `/` still means a live server. Only a transport failure means it is gone.
     * A guessed host has to clear the higher bar of [verifyWith] as well; see [guessMarker].
     */
    private fun reach(client: OkHttpClient, target: String, verifyWith: String? = null): String? = runCatching {
        val url = if (target.startsWith("http")) target else "https://$target/"
        var response = client.newCall(GET(url, PROBE_HEADERS)).execute()

        // AnimeVietsub answers the first request of a session with `403`, a `Set-Cookie` and a
        // script that reloads the page; the retry carrying that cookie gets the real page. Its
        // extension already replays this, but a probe runs on the shared client and would
        // otherwise read the handshake page, find no marker, and call a live site dead.
        if (response.code == 403 && response.headers("Set-Cookie").isNotEmpty()) {
            response.close()
            response = client.newCall(GET(url, PROBE_HEADERS)).execute()
        }

        response.use {
            if (verifyWith != null && !it.body.string().contains(verifyWith, ignoreCase = true)) {
                return@use null
            }
            it.request.url.host
        }
    }.getOrNull()

    private fun String.withScheme(): String =
        if (startsWith("http://") || startsWith("https://")) trimEnd('/') else "https://${trimEnd('/')}"

    companion object {
        private const val KEY_RESOLVED = "resolved_domain"
        private const val KEY_CHECKED_AT = "resolved_domain_checked_at"
        private const val KEY_LAST_PROBE_OK = "resolved_domain_last_probe_ok"
        private const val KEY_FEED = "domain_feed"
        private const val KEY_FEED_AT = "domain_feed_fetched_at"

        /**
         * Where CI publishes the hosts it last saw answering.
         *
         * The same repo and branch the anime extension index is served from, so a reader who can
         * install these sources can already reach this. Refreshed by `.github/workflows/update_domains.yml`.
         *
         * Every source built on this reads the same file, so covering another one — a novel site
         * that moves, say — is an entry in the JSON rather than a change here.
         */
        private const val FEED_URL =
            "https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo-anime/domains.json"

        private val TTL_MILLIS = 6.hours.inWholeMilliseconds
        private val FAILURE_TTL_MILLIS = 5.minutes.inWholeMilliseconds
        private val FEED_TTL_MILLIS = 6.hours.inWholeMilliseconds
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
 * One source's entry in the domain feed.
 *
 * @param domains hosts CI last saw answering, best first.
 * @param announce links the site itself publishes its current location behind.
 * @param marker text that must appear on a page for it to be believed to be this site.
 */
data class MirrorFeed(
    val domains: List<String>,
    val announce: List<String>,
    val marker: String?,
)

/**
 * The feed entry belonging to a source shipping [candidates], or null if there is none.
 *
 * Matched against the shipped hosts rather than passed in by the source, because the sources
 * that need this are already built and installed; see the note on [MirrorResolver]'s constructor.
 * A `match` token is a substring of the site's hosts (`animehay`), which survives the site
 * changing TLD or counter — the two things it actually does.
 *
 * Deliberately hand-rolled over `@Serializable`: a feed is a file on the internet that outlives
 * every app version reading it, so an unknown field or a mistyped entry has to cost that entry
 * at worst, never the parse. Returning null just means the shipped list is used, as before.
 */
fun parseMirrorFeed(body: String, candidates: List<String>): MirrorFeed? {
    if (body.isBlank() || candidates.isEmpty()) return null
    val sources = runCatching {
        Json.parseToJsonElement(body).jsonObject["sources"]?.jsonArray
    }.getOrNull() ?: return null

    val entry = sources.firstOrNull { element ->
        val entry = element as? JsonObject ?: return@firstOrNull false
        val tokens = entry["match"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val hosts = entry.strings("domains")
        tokens.any { token -> candidates.any { it.contains(token, ignoreCase = true) } } ||
            hosts.any { host -> candidates.any { it.equals(host, ignoreCase = true) } }
    } as? JsonObject ?: return null

    val domains = entry.strings("domains")
    val announce = entry.strings("announce")
    if (domains.isEmpty() && announce.isEmpty()) return null

    return MirrorFeed(
        domains = domains,
        announce = announce,
        marker = entry["marker"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.strings(key: String): List<String> = runCatching {
    this[key]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty()
}.getOrDefault(emptyList())

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
