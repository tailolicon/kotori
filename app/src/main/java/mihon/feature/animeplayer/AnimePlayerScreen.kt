package mihon.feature.animeplayer

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.appbars.ReaderToolTile
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.glass
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/**
 * Anime video player (design screen 03) wearing the shared reader chrome: the same top app bar
 * and bottom navigator/tool bars as the manga and novel readers, wired to playback. Gesture
 * seek/brightness/volume, the gradient play cluster, skip-intro chip, lock and PiP stay.
 */
@Composable
fun AnimePlayerScreen(
    player: ExoPlayer,
    title: String,
    episodeLabel: String,
    sourceLabel: String?,
    menuVisible: Boolean,
    onSetMenuVisible: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current
    val accent = AnimeAccent

    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    val currentMenuVisible by rememberUpdatedState(menuVisible)
    val setMenuVisible by rememberUpdatedState(onSetMenuVisible)

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    val showIntroSkip = positionMs in 5_000..120_000 && durationMs > 300_000

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KotoriColors.bgPlayer)
            .pointerInput(locked) {
                if (!locked) {
                    detectTapGestures(
                        onTap = { setMenuVisible(!currentMenuVisible) },
                        onDoubleTap = { offset ->
                            // Double-tap edges = ±10s
                            if (offset.x < size.width / 3f) {
                                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                            } else if (offset.x > size.width * 2 / 3f) {
                                player.seekTo(player.currentPosition + 10_000)
                            }
                        },
                    )
                } else {
                    detectTapGestures(onTap = { setMenuVisible(!currentMenuVisible) })
                }
            }
            .pointerInput(locked) {
                if (!locked) {
                    // Vertical swipe left = brightness, right = volume; the adjust gesture also
                    // dismisses the menu, the counterpart of the manga reader hiding it on zoom.
                    detectVerticalDragGestures { change, dragAmount ->
                        if (abs(dragAmount) > 4f) {
                            if (currentMenuVisible) setMenuVisible(false)
                            val isLeft = change.position.x < size.width / 2f
                            if (isLeft) {
                                val activity = context as? AnimePlayerActivity ?: return@detectVerticalDragGestures
                                val window = activity.window
                                val lp = window.attributes
                                val current = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                lp.screenBrightness = (current - dragAmount / 2000f).coerceIn(0.05f, 1f)
                                window.attributes = lp
                            } else {
                                (context as? AnimePlayerActivity)?.adjustVolume(if (dragAmount < 0) 1 else -1)
                            }
                        }
                    }
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Video surface, rounded bottom corners per mock
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Center controls
                androidx.compose.animation.AnimatedVisibility(
                    visible = menuVisible && !locked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .glass(shape = CircleShape, elevated = true)
                                .clickable { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Replay10,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(
                                    10.dp,
                                    CircleShape,
                                    ambientColor = accent.start.copy(alpha = 0.5f),
                                    spotColor = accent.start.copy(alpha = 0.5f),
                                )
                                .clip(CircleShape)
                                .background(accent.gradient)
                                .clickable {
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .glass(shape = CircleShape, elevated = true)
                                .clickable { player.seekTo(player.currentPosition + 10_000) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Forward10,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // Skip intro chip (bottom-right of video)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showIntroSkip && !locked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .glass(shape = RoundedCornerShape(13.dp), elevated = true)
                            .clickable { player.seekTo(player.currentPosition + 85_000) }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "Bỏ qua intro ›",
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = Color.White,
                        )
                    }
                }
            }

            // Below-video meta (portrait mode)
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                KotoriSectionLabel(text = "Cử chỉ", accent = accent)
                Text(
                    text = "Chạm đôi mép trái/phải: ±10s · vuốt dọc trái: độ sáng · phải: âm lượng",
                    fontFamily = BeVietnamProFamily,
                    fontSize = 10.5.sp,
                    color = KotoriColors.textFaint,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (sourceLabel != null) {
                    Text(
                        text = "Nguồn: $sourceLabel",
                        fontFamily = BeVietnamProFamily,
                        fontSize = 10.5.sp,
                        color = KotoriColors.textMuted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // Player chrome: the exact same app bars as the manga reader, wired to playback. The
        // navigator's slider seeks, its side buttons will move between episodes once the player
        // receives a playlist; until then they render disabled exactly like a single chapter.
        ReaderAppBars(
            visible = menuVisible,
            mangaTitle = title,
            chapterTitle = episodeLabel,
            navigateUp = onNavigateUp,
            onClickTopAppBar = {},
            bookmarked = false,
            onToggleBookmarked = null,
            onOpenInWebView = null,
            onOpenInBrowser = null,
            onShare = null,
            chapterNavigatorType = ChapterNavigatorType.HORIZONTAL_LTR,
            verticalNavigatorHeight = 1f,
            onNextChapter = {},
            enabledNext = false,
            onPreviousChapter = {},
            enabledPrevious = false,
            currentPage = (positionMs / 1000).toInt().coerceAtLeast(1),
            totalPages = (durationMs / 1000).toInt(),
            onPageIndexChange = { index ->
                val target = (index + 1) * 1000L
                player.seekTo(target)
                positionMs = target
            },
            onPageIndexChangeFinished = {},
            pageLabel = { seconds -> formatTime(seconds * 1000L) },
            continuousSlider = true,
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .pointerInput(Unit) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderToolTile(
                        painter = rememberVectorPainter(Icons.Filled.Subtitles),
                        label = "Phụ đề",
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolTile(
                        painter = rememberVectorPainter(Icons.Filled.Speed),
                        label = "${speed}x",
                        onClick = { speed = player.cycleSpeed() },
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolTile(
                        painter = rememberVectorPainter(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen),
                        label = "Khóa",
                        onClick = { locked = !locked },
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolTile(
                        painter = rememberVectorPainter(Icons.Filled.PictureInPictureAlt),
                        label = "PiP",
                        onClick = onEnterPip,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        )
    }
}
