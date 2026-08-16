package eu.kanade.tachiyomi.source.novel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Single trust boundary for inline illustration URLs in novel chapters.
 *
 * Chapter HTML is author-controlled, so this is the gate that decides whether a URL found in it may
 * become a network request. The rule is a *denylist*, not an allowlist: novel sites let authors host
 * pictures anywhere, so enumerating CDNs is a losing game — an unlisted host used to mean the
 * illustration silently vanished from the chapter. Instead every public HTTPS host is allowed and
 * only the shapes that could be used to reach something other than a public image are refused:
 *
 *  - non-HTTPS schemes, so a chapter cannot downgrade a request or hand off to `file:`/`intent:`;
 *  - IP-address literals, which is how SSRF probes address loopback (`127.0.0.1`), private ranges
 *    (`10/8`, `192.168/16`), link-local (`169.254/16`, including the cloud metadata endpoint) and
 *    their IPv6 equivalents — real image CDNs are always named;
 *  - names that cannot resolve on the public internet: single-label hosts like `localhost` or an
 *    intranet machine name, and the reserved `.local`, `.internal`, `.localdomain`, `.home.arpa`,
 *    `.onion` and `.test` suffixes.
 *
 * Being unable to name every safe host is exactly why this is written the other way round: the
 * dangerous set is small, closed and knowable, while the safe set is not.
 */
object NovelImagePolicy {

    /**
     * Sites that serve illustrations only to requests that look like they came from their own pages.
     * A miss is not fatal — the header is simply omitted and the CDN usually still answers — so this
     * maps host suffixes rather than trying to be exhaustive.
     */
    private val REFERERS: List<Pair<String, String>> = listOf(
        "hako.vip" to "https://docln.net/",
        "docln.net" to "https://docln.net/",
        "ln.hako.vn" to "https://ln.hako.vn/",
        "wattpad.com" to "https://www.wattpad.com/",
        "wp.com" to "https://www.wattpad.com/",
        "novelfever.com" to "https://novelfever.com/",
    )

    /** Suffixes reserved for names that never resolve publicly. */
    private val PRIVATE_SUFFIXES = listOf(
        ".local",
        ".localhost",
        ".localdomain",
        ".internal",
        ".intranet",
        ".lan",
        ".home.arpa",
        ".onion",
        ".test",
        ".invalid",
        ".example",
    )

    fun isTrusted(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isTrusted) == true

    fun isTrusted(url: HttpUrl): Boolean {
        if (url.scheme != "https") return false
        val host = url.host.lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        // A hostname with no dot cannot be a public name, and every IP literal — v4, v6 or the
        // mixed forms — is either digits-and-dots or contains a colon.
        if (!host.contains('.')) return false
        if (host.contains(':')) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        return PRIVATE_SUFFIXES.none { host.endsWith(it) }
    }

    /** Referer the illustration's host expects, or null when it does not need one. */
    fun refererFor(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return null
        return REFERERS.firstOrNull { (suffix, _) -> host == suffix || host.endsWith(".$suffix") }
            ?.second
    }
}
