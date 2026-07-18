package eu.kanade.tachiyomi.source.novel.builtin

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val login = loginUrl ?: return
        val context = screen.context
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
        const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
