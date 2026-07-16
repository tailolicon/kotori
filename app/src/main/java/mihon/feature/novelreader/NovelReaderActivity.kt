package mihon.feature.novelreader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.theme.TachiyomiTheme
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Reader for prose chapters, the novel counterpart of [eu.kanade.tachiyomi.ui.reader.ReaderActivity].
 *
 * Takes a chapter *reference* rather than its text: chapter bodies routinely run past the ~1MB
 * Binder limit, and an Intent extra also leaves nowhere to record how far the reader got. The
 * model fetches the body itself and writes progress back to the same chapter/history rows the
 * manga reader uses, so "continue reading" works identically across content types.
 */
class NovelReaderActivity : ComponentActivity() {

    private val viewModel by lazy {
        NovelReaderViewModel(
            mangaId = intent.getLongExtra(EXTRA_MANGA, -1),
            initialChapterId = intent.getLongExtra(EXTRA_CHAPTER, -1).takeIf { it != -1L },
            scope = lifecycleScope,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferences = Injekt.get<NovelReaderPreferences>()

        val localText = intent.getStringExtra(EXTRA_TEXT)

        setContent {
            TachiyomiTheme {
                if (localText != null) {
                    NovelReaderScreen(
                        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                        chapterLabel = intent.getStringExtra(EXTRA_CHAPTER_LABEL).orEmpty(),
                        content = localText,
                        startPercent = 0,
                        onProgressChanged = {},
                        preferences = preferences,
                        onNavigateUp = ::finish,
                    )
                } else {
                    NovelReaderContent(
                        viewModel = viewModel,
                        preferences = preferences,
                        onNavigateUp = ::finish,
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen must not lose the position; onStop is too late if the process dies.
        if (intent.getStringExtra(EXTRA_TEXT) == null) viewModel.flushProgress()
    }

    companion object {
        private const val EXTRA_MANGA = "manga"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHAPTER_LABEL = "chapter_label"
        private const val EXTRA_TEXT = "text"

        fun newIntent(context: Context, mangaId: Long, chapterId: Long?): Intent =
            Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_MANGA, mangaId)
                chapterId?.let { putExtra(EXTRA_CHAPTER, it) }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        /**
         * Entry point for local .txt files, which have no library row to load from or record
         * progress against, so the text comes along directly.
         */
        fun newTextIntent(context: Context, title: String, chapterLabel: String, text: String): Intent =
            Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHAPTER_LABEL, chapterLabel)
                putExtra(EXTRA_TEXT, text)
            }
    }
}
