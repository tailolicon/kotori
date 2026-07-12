package mihon.feature.novelreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import eu.kanade.presentation.theme.TachiyomiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Standalone novel reader (design screens 10/22). Reads plain-text content
 * passed via extras or a text `Uri` (e.g. a local .txt chapter). Novel source
 * integration feeds this the chapter body once novel extensions exist.
 */
class NovelReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Novel"
        val chapterLabel = intent.getStringExtra(EXTRA_CHAPTER) ?: "Chương 1"
        val textExtra = intent.getStringExtra(EXTRA_TEXT)
        val uri: Uri? = intent.data

        val preferences = Injekt.get<NovelReaderPreferences>()

        setContent {
            TachiyomiTheme {
                val content by produceState(initialValue = textExtra ?: "") {
                    if (textExtra == null && uri != null) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openInputStream(uri)?.use {
                                    it.readBytes().decodeToString()
                                }
                            }.getOrNull() ?: "Không đọc được nội dung."
                        }
                    }
                }
                NovelReaderScreen(
                    title = title,
                    chapterLabel = chapterLabel,
                    content = content,
                    progressPercent = 0,
                    progressLabel = chapterLabel,
                    preferences = preferences,
                    onNavigateUp = ::finish,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_TEXT = "text"

        fun newIntent(
            context: Context,
            title: String,
            chapterLabel: String,
            text: String,
        ): Intent {
            return Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHAPTER, chapterLabel)
                putExtra(EXTRA_TEXT, text)
            }
        }
    }
}
