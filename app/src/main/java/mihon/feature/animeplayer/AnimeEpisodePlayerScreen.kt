package mihon.feature.animeplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.formatEpisodeNumber
import kotlin.math.abs
import kotlinx.coroutines.delay
import tachiyomi.domain.items.episode.model.Episode

private val AnimeGradient = Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFFF472B6)))
private val AnimeLight = Color(0xFFC4B5FD)

/**
 * Portrait Kotori anime player (design screen 03): 16:9 video with the full control set (play/pause,
 * ±10s, draggable seek, skip-intro +85s, fullscreen, speed/quality/subtitle/lock/PiP chips,
 * brightness & volume gestures) and the scrollable episode list below — anchored to the current
 * episode. Tapping an episode switches playback in place.
 *
 * Overlays use plain `if (visible)` blocks rather than [androidx.compose.animation.AnimatedVisibility]
 * on purpose: inside this Box-in-Column the AnimatedVisibility overload resolves to the wrong
 * (ColumnScope) receiver and fails to compile / lay out.
 */
@Composable
fun AnimeEpisodePlayerScreen(
    viewModel: AnimePlayerViewModel,
    onNavigateUp: () -> Unit,
    onOpenMpv: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current
    val player = viewModel.player
    val episodes = viewModel.episodes
    val current = viewModel.currentEpisode

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var fullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            viewModel.saveActiveProgress()
            delay(1000)
        }
    }

    val showIntroSkip = positionMs in 5_000..120_000 && durationMs > 300_000

    val listState = rememberLazyListState()
    LaunchedEffect(current?.id, episodes.size) {
        val idx = episodes.indexOfFirst { it.id == current?.id }
        if (idx >= 0) listState.scrollToItem(idx)
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        (context as? AnimePlayerActivity)?.setFullscreen(fullscreen)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KotoriColors.bgPlayer),
    ) {
        // Video surface + overlays
        Box(
            modifier = Modifier
                .then(
                    if (fullscreen) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    },
                )
                .background(Color.Black)
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                if (offset.x < size.width / 3f) {
                                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                                } else if (offset.x > size.width * 2 / 3f) {
                                    player.seekTo(player.currentPosition + 10_000)
                                }
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (!locked) {
                        detectVerticalDragGestures { change, dragAmount ->
                            if (abs(dragAmount) > 4f) {
                                val activity = context as? AnimePlayerActivity ?: return@detectVerticalDragGestures
                                if (change.position.x < size.width / 2f) {
                                    val window = activity.window
                                    val lp = window.attributes
                                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                    lp.screenBrightness = (cur - dragAmount / 2000f).coerceIn(0.05f, 1f)
                                    window.attributes = lp
                                } else {
                                    activity.adjustVolume(if (dragAmount < 0) 1 else -1)
                                }
                            }
                        }
                    }
                },
        ) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
                modifier = Modifier.fillMaxSize(),
            )

            if (controlsVisible) {
                // Scrims for legibility over bright frames
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
                )

                // Top bar: back + episode label + fullscreen
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(end = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable(onClick = onNavigateUp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = current?.let { "T${formatEpisodeNumber(it.episodeNumber)}" }.orEmpty(),
                            color = Color.White,
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(26.dp)
                            .clickable { toggleFullscreen() },
                    )
                }
            }

            // Center controls
            if (controlsVisible && !locked && !viewModel.loading && viewModel.errorMessage == null) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    CircleControl(Icons.Filled.Replay10, 44.dp) {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(AnimeGradient)
                            .clickable { if (player.isPlaying) player.pause() else player.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    CircleControl(Icons.Filled.Forward10, 44.dp) {
                        player.seekTo(player.currentPosition + 10_000)
                    }
                }
            }

            if (viewModel.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFC084FC),
                )
            }

            viewModel.errorMessage?.let { msg ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(msg, color = Color.White, fontFamily = BeVietnamProFamily, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(KotoriShapes.chip)
                            .background(AnimeGradient)
                            .clickable(onClick = onOpenMpv)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Mở bằng trình phát mpv", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Skip intro (+85s)
            if (showIntroSkip && !locked && controlsVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 40.dp)
                        .glass(shape = RoundedCornerShape(13.dp), elevated = true)
                        .clickable { player.seekTo(player.currentPosition + 85_000) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "Bỏ qua intro ›",
                        color = Color.White,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                    )
                }
            }
        }

        if (!fullscreen) {
            // Seek bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(formatMs(positionMs), color = Color(0xFFF0ABFC), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { f -> if (durationMs > 0) player.seekTo((f * durationMs).toLong()) },
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF8B5CF6),
                        inactiveTrackColor = Color(0x47FFFFFF),
                    ),
                )
                Text(formatMs(durationMs), color = KotoriColors.textMuted, fontSize = 10.sp)
            }

            // Quick-setting chips
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerChip(Icons.Filled.Subtitles, "Phụ đề", AnimeLight) {}
                PlayerChip(Icons.Filled.Speed, "${speed}x", KotoriColors.textSecondary) { speed = player.cycleSpeed() }
                PlayerChip(Icons.Filled.HighQuality, "Auto", KotoriColors.textSecondary) {}
                PlayerChip(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    "Khóa",
                    if (locked) AnimeLight else KotoriColors.textSecondary,
                ) { locked = !locked }
                PlayerChip(Icons.Filled.PictureInPictureAlt, "PiP", KotoriColors.textSecondary) { onEnterPip() }
                PlayerChip(Icons.Filled.SkipNext, "mpv", KotoriColors.textSecondary) { onOpenMpv() }
            }

            Text(
                text = "DANH SÁCH TẬP",
                color = KotoriColors.textMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(episodes, key = { _, e -> e.id }) { _, episode ->
                    EpisodeRow(
                        episode = episode,
                        coverUrl = viewModel.anime?.thumbnailUrl,
                        active = episode.id == current?.id,
                        onClick = { viewModel.loadEpisode(episode.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleControl(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .glass(shape = CircleShape, elevated = true)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PlayerChip(icon: ImageVector, label: String, iconTint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .glass(shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(
            label,
            color = KotoriColors.textPrimary,
            fontFamily = BeVietnamProFamily,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    coverUrl: String?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val progress = if (episode.totalSeconds > 0L && episode.lastSecondSeen in 1 until episode.totalSeconds) {
        (episode.lastSecondSeen.toFloat() / episode.totalSeconds).coerceIn(0f, 1f)
    } else {
        null
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(KotoriShapes.row)
            .then(
                if (active) {
                    Modifier
                        .background(Color(0xFF8B5CF6).copy(alpha = 0.16f))
                        .border(1.dp, AnimeLight.copy(alpha = 0.55f), KotoriShapes.row)
                } else {
                    Modifier.glass(shape = KotoriShapes.row)
                },
            )
            .clickable(onClick = onClick)
            .alpha(if (episode.seen) 0.6f else 1f),
    ) {
        Row(
            modifier = Modifier.padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(86.dp)
                    .height(52.dp)
                    .clip(KotoriShapes.thumbSmall)
                    .background(AnimeGradient),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(model = coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Text(
                    text = "T${formatEpisodeNumber(episode.episodeNumber)}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.name,
                    color = KotoriColors.textPrimary,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        episode.seen -> "đã xem"
                        progress != null -> "xem dở ${(progress * 100).toInt()}%"
                        else -> "chưa xem"
                    },
                    color = KotoriColors.textMuted,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (episode.seen) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = KotoriColors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (progress != null && !episode.seen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.10f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(AnimeGradient),
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
