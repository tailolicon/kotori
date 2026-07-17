package mihon.feature.novelreader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.util.system.setDefaultSettings

/**
 * Renders a chapter by embedding the source's own page.
 *
 * Used when a [eu.kanade.tachiyomi.source.NovelSource] reports a `chapterWebUrl`: the site itself
 * assembles the chapter, which is the only way to read sites that hand their text to their own
 * scripts. The page keeps its own typography, and its text cannot be captured for downloads — that
 * is inherent to letting the site render, not an oversight.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelWebChapterView(url: String, modifier: Modifier = Modifier) {
    var progress by remember { mutableIntStateOf(0) }

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
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            progress = 0
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 100
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { if (it.url != url) it.loadUrl(url) },
        )
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxSize().align(Alignment.TopCenter),
            )
        }
    }
}
