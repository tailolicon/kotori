package eu.kanade.tachiyomi.source.online

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.MirrorResolver
import eu.kanade.tachiyomi.source.WebViewLoginSource

/**
 * A [NovelHttpSource] for a site that answers on more than one hostname.
 *
 * Over [NovelHttpSource] it adds the mirror preference and keeps [domain] pointed at a host that
 * responds. A source that also needs an account implements [WebViewLoginSource]; the app puts that
 * button in the settings screen, since only it owns the WebView.
 */
abstract class MirroredNovelSource : NovelHttpSource(), ConfigurableSource {

    override val lang: String = "vi"

    /**
     * Hostnames the site answers on, most-preferred first; empty when it has only one.
     *
     * These exist because reachability is a property of the reader's network, not of the site. A
     * Vietnamese ISP resetting the TLS handshake for one hostname while happily serving the same
     * content from another is routine, and it is indistinguishable in the app from the site being
     * down. Nothing here can detect that, so the choice is the reader's to make.
     */
    open val mirrors: List<String> = emptyList()

    /**
     * The hostname requests actually go to.
     *
     * Read fresh each time rather than captured, so switching mirror takes effect on the next
     * request instead of needing the app restarted.
     */
    private val mirrorResolver by lazy { MirrorResolver(getSourcePreferences(), mirrors) }

    /**
     * The hostname requests actually go to.
     *
     * A mirror the reader picked by hand is honoured as-is — switching mirror is often about
     * which one carries a given series, not which one is up, and that is a judgement only they
     * can make. Otherwise [MirrorResolver] keeps this pointed at a host that answers, following
     * redirects so a domain move is picked up on its own.
     */
    protected val domain: String
        get() {
            val picked = getSourcePreferences().getString(MIRROR_KEY, null)?.takeIf { it in mirrors }
            if (picked != null) return picked
            return mirrorResolver.baseUrl(null).removePrefix("https://").removePrefix("http://")
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        if (mirrors.size > 1) {
            screen.addPreference(
                ListPreference(context).apply {
                    key = MIRROR_KEY
                    title = "Tên miền"
                    entries = mirrors.toTypedArray()
                    entryValues = mirrors.toTypedArray()
                    setDefaultValue(mirrors.first())
                    summary = "%s\nĐổi sang tên miền khác nếu nhà mạng chặn tên miền đang dùng."
                },
            )
        }
    }

    companion object {
        const val MIRROR_KEY: String = "novel_mirror_domain"

        const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
