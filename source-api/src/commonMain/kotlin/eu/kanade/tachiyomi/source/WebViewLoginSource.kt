package eu.kanade.tachiyomi.source

/**
 * A source whose account-gated content needs the reader signed in through the app's WebView.
 *
 * The source only names the page. The app puts the button in the source's settings and owns the
 * WebView, which is what lets an extension declare a login at all — it cannot reach the activity,
 * and it should not: the reader types their own credentials into the site's own page, the cookies
 * land in the shared [android.webkit.CookieManager], and this source's OkHttp client picks them up
 * from there. Nothing in between ever sees the password.
 */
interface WebViewLoginSource : Source {

    /** The site's login page. */
    val loginUrl: String
}
