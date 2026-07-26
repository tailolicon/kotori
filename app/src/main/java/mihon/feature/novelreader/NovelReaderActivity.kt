package mihon.feature.novelreader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.feature.novelreader.tts.NovelTtsPreferences
import tachiyomi.core.common.Constants
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

    private val readerPreferences = Injekt.get<ReaderPreferences>()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    /**
     * Same chrome contract as the manga reader: one visibility flag drives the app bars and the
     * system bars together, and survives chapter changes because it lives at the activity level.
     */
    private val menuVisibleState = mutableStateOf(true)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setMenuVisibility(menuVisibleState.value)

        val preferences = Injekt.get<NovelReaderPreferences>()
        val ttsPreferences = Injekt.get<NovelTtsPreferences>()

        val localText = intent.getStringExtra(EXTRA_TEXT)

        setContent {
            TachiyomiTheme {
                val menuVisible by menuVisibleState
                if (localText != null) {
                    NovelReaderScreen(
                        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                        chapterLabel = intent.getStringExtra(EXTRA_CHAPTER_LABEL).orEmpty(),
                        content = localText,
                        startPercent = 0,
                        onProgressChanged = {},
                        preferences = preferences,
                        ttsPreferences = ttsPreferences,
                        onNavigateUp = ::finish,
                        menuVisible = menuVisible,
                        onSetMenuVisible = ::setMenuVisibility,
                    )
                } else {
                    NovelReaderContent(
                        viewModel = viewModel,
                        preferences = preferences,
                        ttsPreferences = ttsPreferences,
                        onNavigateUp = ::finish,
                        menuVisible = menuVisible,
                        onSetMenuVisible = ::setMenuVisibility,
                        onOpenEntry = ::openEntryScreen,
                    )
                }
            }
        }
    }

    /**
     * Sets the visibility of the menu according to [visible], mirroring the manga reader: the
     * system bars come back with the menu and leave with it when the reader is fullscreen.
     */
    private fun setMenuVisibility(visible: Boolean) {
        menuVisibleState.value = visible
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        setMenuVisibility(menuVisibleState.value)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(menuVisibleState.value)
        }
    }

    /** Opens the entry's detail screen, the same top-bar tap behaviour as the manga reader. */
    private fun openEntryScreen() {
        val mangaId = intent.getLongExtra(EXTRA_MANGA, -1).takeIf { it != -1L } ?: return
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Constants.SHORTCUT_MANGA
                putExtra(Constants.MANGA_EXTRA, mangaId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
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
