package mihon.feature.animeplayer

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.UnboundedFamily
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
 * Anime player UI (design screen 03): glass controls, gradient play button,
 * skip-intro chip, gradient seek bar with pink time, quick-setting chips,
 * gesture seek/brightness/volume, lock and PiP.
 */
@Composable
fun AnimePlayerScreen(
    player: ExoPlayer,
    title: String,
    episodeLabel: String,
    sourceLabel: String?,
    onNavigateUp: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current
    val accent = AnimeAccent

    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            bufferedMs = player.bufferedPosition
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
                        onTap = { controlsVisible = !controlsVisible },
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
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                }
            }
            .pointerInput(locked) {
                if (!locked) {
                    // Vertical swipe left = brightness, right = volume
                    detectVerticalDragGestures { change, dragAmount ->
                        if (abs(dragAmount) > 4f) {
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
                    visible = controlsVisible && !locked,
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

                // Top bar overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(onClick = onNavigateUp),
                        )
                        Text(
                            text = "$title · $episodeLabel",
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Subtitles,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Skip intro chip (bottom-right of video)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showIntroSkip && !locked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 44.dp),
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

                // Seek bar overlay (bottom of video)
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible && !locked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = formatTime(positionMs),
                            fontFamily = UnboundedFamily,
                            fontSize = 10.sp,
                            color = KotoriColors.highlightPink,
                        )
                        Slider(
                            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                            onValueChange = { fraction ->
                                if (durationMs > 0) player.seekTo((fraction * durationMs).toLong())
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = accent.start,
                                inactiveTrackColor = Color(0x47FFFFFF),
                            ),
                        )
                        Text(
                            text = formatTime(durationMs),
                            fontFamily = UnboundedFamily,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            // Below-video: quick-setting chips + meta (portrait mode)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                ) {
                    item {
                        PlayerChip(icon = { Icon(Icons.Filled.Subtitles, null, tint = accent.light, modifier = Modifier.size(16.dp)) }, label = "Phụ đề · Việt") {}
                    }
                    item {
                        PlayerChip(
                            icon = { Icon(Icons.Filled.Speed, null, tint = KotoriColors.textSecondary, modifier = Modifier.size(16.dp)) },
                            label = "${speed}x",
                        ) { speed = player.cycleSpeed() }
                    }
                    item {
                        PlayerChip(icon = { Icon(Icons.Filled.HighQuality, null, tint = KotoriColors.textSecondary, modifier = Modifier.size(16.dp)) }, label = "Auto") {}
                    }
                    item {
                        PlayerChip(
                            icon = {
                                Icon(
                                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    null,
                                    tint = if (locked) accent.light else KotoriColors.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = "Khóa",
                        ) { locked = !locked }
                    }
                    item {
                        PlayerChip(
                            icon = { Icon(Icons.Filled.PictureInPictureAlt, null, tint = KotoriColors.textSecondary, modifier = Modifier.size(16.dp)) },
                            label = "PiP",
                        ) { onEnterPip() }
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 18.dp)) {
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
        }
    }
}

@Composable
private fun PlayerChip(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .glass(shape = RoundedCornerShape(14.dp), elevated = true)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = KotoriColors.textSecondary,
        )
    }
}
