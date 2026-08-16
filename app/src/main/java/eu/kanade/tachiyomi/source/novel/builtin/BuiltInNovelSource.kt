package eu.kanade.tachiyomi.source.novel.builtin

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.MirrorResolver
import eu.kanade.tachiyomi.source.online.NovelHttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewActivity

/**
 * Base for novel sources compiled into the app rather than installed as an extension.
 *
 * Mirrors the anime side's `BuiltInHttpSource`: over [NovelHttpSource] it adds [iconUrl] (a built-in
 * source has no extension package to pull an icon from) and, when the site has one, a WebView login.
 */
abstract class BuiltInNovelSource : NovelHttpSource(), ConfigurableSource {

    override val lang: String = "vi"

    /** Remote icon shown in Browse, standing in for the missing extension package icon. */
    abstract val iconUrl: String

    /**
     * The site's login page. When set, a "Đăng nhập" action appears in the source's settings that
     * opens it in a WebView. The user signs in with their own account there; the resulting cookies
     * land in the shared [android.webkit.CookieManager], which this source's OkHttp client reads
     * through [eu.kanade.tachiyomi.network.AndroidCookieJar], so account-gated chapters then load.
     * The app never sees the credentials.
     */
    open val loginUrl: String? = null

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
        val login = loginUrl ?: return
        screen.addPreference(
            Preference(context).apply {
                title = "Đăng nhập bằng WebView"
                summary = "Mở trang đăng nhập của $name để xem nội dung cần tài khoản. " +
                    "Bạn tự nhập tài khoản của mình; app không lưu mật khẩu."
                setOnPreferenceClickListener {
                    context.startActivity(WebViewActivity.newIntent(context, login, id, name))
                    true
                }
            },
        )
    }

    companion object {
        const val MIRROR_KEY: String = "novel_mirror_domain"

        const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
