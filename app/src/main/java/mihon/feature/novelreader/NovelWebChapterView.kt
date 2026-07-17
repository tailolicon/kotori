package mihon.feature.novelreader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import mihon.feature.novelreader.NovelReaderPreferences.NovelFont

/**
 * Renders a chapter by embedding the source's own page, restyled to match the native reader.
 *
 * Used when a [eu.kanade.tachiyomi.source.NovelSource] reports a `chapterWebUrl`, which is how
 * sites that hand their chapter text to their own scripts are read. The site assembles the chapter;
 * this strips its chrome and imposes the reader's paper theme and typography, so the result reads
 * like the rest of the app rather than like a web page.
 *
 * The page's text stays inside the page — that is inherent to letting the site render it, and is
 * why chapters read this way cannot be downloaded for offline use.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun NovelWebChapterView(
    url: String,
    preferences: NovelReaderPreferences,
    startPercent: Int,
    onProgressChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontSize = preferences.fontSize.get()
    val font = preferences.fontFamily.get()
    val paper = preferences.theme.get().paper()
    val spacing = preferences.lineSpacing.get().multiplier

    val css = remember(fontSize, font, paper, spacing) {
        readerCss(
            background = paper.background.toArgb().toCssHex(),
            ink = paper.ink.toArgb().toCssHex(),
            accent = paper.accent.toArgb().toCssHex(),
            fontFamily = font.cssStack(),
            fontSizePx = fontSize,
            lineHeight = spacing,
        )
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setDefaultSettings()
                    setBackgroundColor(paper.background.toArgb())
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onScroll(percent: Int) = onProgressChanged(percent)
                        },
                        "KotoriReader",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            // Hide early so the site's own layout never flashes through.
                            view?.evaluateJavascript("document.documentElement.style.opacity='0'", null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(restyleScript(css, startPercent), null)
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { if (it.url != url) it.loadUrl(url) },
        )
    }
}

private fun Int.toCssHex(): String = String.format("#%06X", 0xFFFFFF and this)

private fun NovelFont.cssStack(): String = when (this) {
    NovelFont.LITERATA -> "'Literata', Georgia, serif"
    NovelFont.NOTO_SERIF -> "'Noto Serif', Georgia, serif"
    NovelFont.BE_VIETNAM -> "'Be Vietnam Pro', system-ui, sans-serif"
}

/**
 * Strips the site's chrome and imposes the reader's look. Everything outside the chapter body is
 * hidden rather than removed, so the page's own scripts keep whatever nodes they expect to find.
 */
private fun readerCss(
    background: String,
    ink: String,
    accent: String,
    fontFamily: String,
    fontSizePx: Int,
    lineHeight: Float,
): String = """
    html, body {
      background: $background !important;
      color: $ink !important;
      margin: 0 !important;
      padding: 0 !important;
    }
    header, footer, nav, aside, iframe, ins, .ads, [class*="ad-"], [id*="ads"],
    .navbar, .header, .footer, .basic-section:not(#chapter-content),
    .rating, .comment, #comment, .fb-comments, .series-nav, .chapter-nav,
    .breadcrumb, .basic-tag, .sticky, .toast, .modal, .long-text > a {
      display: none !important;
    }
    #chapter-content, .long-text {
      display: block !important;
      background: $background !important;
      color: $ink !important;
      font-family: $fontFamily !important;
      font-size: ${fontSizePx}px !important;
      line-height: $lineHeight !important;
      max-width: 100% !important;
      margin: 0 auto !important;
      padding: 24px 22px 64px 22px !important;
      /* The site marks the body no-select; that only blocks the reader's own text handles. */
      user-select: text !important;
      -webkit-user-select: text !important;
    }
    #chapter-content p, .long-text p {
      color: $ink !important;
      font-family: $fontFamily !important;
      font-size: ${fontSizePx}px !important;
      line-height: $lineHeight !important;
      margin: 0 0 1.1em 0 !important;
      text-align: justify !important;
    }
    #chapter-content img, .long-text img {
      max-width: 100% !important;
      height: auto !important;
      border-radius: 10px !important;
    }
    #chapter-content a, .long-text a { color: $accent !important; }
    ::selection { background: $accent !important; }
""".trimIndent()

/**
 * Applies [css], lifts the chapter body to be the whole page, restores the saved position and
 * reports scrolling back to the reader.
 */
private fun restyleScript(css: String, startPercent: Int): String = """
(function() {
  var style = document.createElement('style');
  style.textContent = ${css.toJsString()};
  document.head.appendChild(style);

  // Promote the chapter body to the top level so the site's wrappers can't re-impose their layout.
  var body = document.querySelector('#chapter-content') || document.querySelector('.long-text');
  if (body) {
    document.body.innerHTML = '';
    document.body.appendChild(body);
  }

  function percent() {
    var h = document.documentElement.scrollHeight - window.innerHeight;
    return h > 0 ? Math.round(window.scrollY * 100 / h) : 0;
  }
  window.addEventListener('scroll', function() {
    if (window.KotoriReader) window.KotoriReader.onScroll(percent());
  }, { passive: true });

  var start = $startPercent;
  if (start > 0) {
    // Wait for layout, or scrollHeight is still the pre-restyle value and the jump lands short.
    requestAnimationFrame(function() {
      var h = document.documentElement.scrollHeight - window.innerHeight;
      if (h > 0) window.scrollTo(0, h * start / 100);
      document.documentElement.style.opacity = '1';
    });
  } else {
    document.documentElement.style.opacity = '1';
  }
})();
""".trimIndent()

/** Embeds a string into JS source safely. */
private fun String.toJsString(): String = buildString {
    append('"')
    this@toJsString.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }
    append('"')
}
