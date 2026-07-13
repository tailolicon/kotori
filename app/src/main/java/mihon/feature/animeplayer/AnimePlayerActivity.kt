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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import eu.kanade.presentation.theme.TachiyomiTheme

/**
 * Anime video player (design screen 03) built on androidx.media3/ExoPlayer.
 * Receives a stream/file URL via intent; anime sources feed real episodes
 * once anime extensions exist.
 */
class AnimePlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Anime"
        val episodeLabel = intent.getStringExtra(EXTRA_EPISODE) ?: "T1"
        val sourceLabel = intent.getStringExtra(EXTRA_SOURCE)

        val exoPlayer = ExoPlayer.Builder(this).build().also { player = it }
        if (url != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        setContent {
            TachiyomiTheme {
                AnimePlayerScreen(
                    player = exoPlayer,
                    title = title,
                    episodeLabel = episodeLabel,
                    sourceLabel = sourceLabel,
                    onNavigateUp = ::finish,
                    onEnterPip = ::enterPip,
                )
            }
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
        ): Intent {
            return Intent(context, AnimePlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_EPISODE, episodeLabel)
                putExtra(EXTRA_SOURCE, sourceLabel)
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
