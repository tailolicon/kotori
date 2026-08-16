package mihon.feature.animeplayer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Anime video player (design screen 03) built on androidx.media3/ExoPlayer.
 * Receives a stream/file URL via intent; anime sources feed real episodes
 * once anime extensions exist.
 */
class AnimePlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    private val readerPreferences = Injekt.get<ReaderPreferences>()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    /** Same chrome contract as the manga reader: one flag drives the app bars and the system bars. */
    private val menuVisibleState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setMenuVisibility(menuVisibleState.value)

        val url = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Anime"
        val episodeLabel = intent.getStringExtra(EXTRA_EPISODE) ?: "T1"
        val sourceLabel = intent.getStringExtra(EXTRA_SOURCE)

        // Sources hand back the headers their CDN expects — a Referer tied to the embed host, the
        // desktop User-Agent the stream was authorised for. Playing the url on its own dropped all
        // of that, which works right up until a host starts checking.
        val requestHeaders = intent.getStringArrayExtra(EXTRA_HEADERS)
            ?.toList()
            ?.chunked(2)
            ?.filter { it.size == 2 }
            ?.associate { (name, value) -> name to value }
            .orEmpty()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
            .setAllowCrossProtocolRedirects(true)
            .apply { requestHeaders["User-Agent"]?.let(::setUserAgent) }

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { player = it }
        if (url != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        setContent {
            TachiyomiTheme {
                val menuVisible by menuVisibleState
                AnimePlayerScreen(
                    player = exoPlayer,
                    title = title,
                    episodeLabel = episodeLabel,
                    sourceLabel = sourceLabel,
                    menuVisible = menuVisible,
                    onSetMenuVisible = ::setMenuVisibility,
                    onNavigateUp = ::finish,
                    onEnterPip = ::enterPip,
                )
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
            windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
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

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }

    fun adjustVolume(delta: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    companion object {
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_SOURCE = "source"

        fun newIntent(
            context: Context,
            url: String,
            title: String,
            episodeLabel: String,
            sourceLabel: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): Intent {
            return Intent(context, AnimePlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_EPISODE, episodeLabel)
                putExtra(EXTRA_SOURCE, sourceLabel)
                // Flattened to name/value pairs: a Bundle of extras would need the receiver to know
                // the keys, and there is no fixed set of them.
                putExtra(EXTRA_HEADERS, headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
        }
    }
}

/** Playback speeds cycled by the speed chip. */
internal val PlayerSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

internal fun Player.cycleSpeed(): Float {
    val current = playbackParameters.speed
    val next = PlayerSpeeds[(PlayerSpeeds.indexOfFirst { it >= current - 0.01f } + 1) % PlayerSpeeds.size]
    setPlaybackSpeed(next)
    return next
}
