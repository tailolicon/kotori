package mihon.feature.animeplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.delay
import tachiyomi.domain.items.episode.model.Episode

private val AnimeGradient = Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFFF472B6)))

/**
 * Portrait Kotori anime player (design screen 03): 16:9 video on top, playback controls, and the
 * scrollable episode list below — anchored to the current episode so the next episodes sit under it
 * and the previous ones are a scroll away. Tapping an episode switches playback in place.
 */
@Composable
fun AnimeEpisodePlayerScreen(
    viewModel: AnimePlayerViewModel,
    onNavigateUp: () -> Unit,
    onOpenMpv: () -> Unit,
) {
    val player = viewModel.player
    val episodes = viewModel.episodes
    val current = viewModel.currentEpisode

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            viewModel.saveActiveProgress()
            delay(1000)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(current?.id, episodes.size) {
        val idx = episodes.indexOfFirst { it.id == current?.id }
        if (idx >= 0) listState.scrollToItem(idx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KotoriColors.bgPlayer),
    ) {
        // Video surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
                modifier = Modifier.fillMaxSize(),
            )

            // Top bar: back + title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onNavigateUp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = viewModel.anime?.title.orEmpty(),
                    color = Color.White,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Center controls
            if (!viewModel.loading) {
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
        }

        // Seek bar
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(AnimeGradient),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(positionMs), color = Color(0xFFF0ABFC), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(formatMs(durationMs), color = KotoriColors.textMuted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "DANH SÁCH TẬP",
            color = KotoriColors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
        )

        // Episode list — current + next below, previous above (scroll up to reveal)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
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

@Composable
private fun CircleControl(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
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
                        .border(1.dp, Color(0xFFC4B5FD).copy(alpha = 0.55f), KotoriShapes.row)
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
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
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
