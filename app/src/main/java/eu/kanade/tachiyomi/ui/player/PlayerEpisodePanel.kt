package eu.kanade.tachiyomi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.data.database.models.anime.Episode

private val AnimeGradient = Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFFF472B6)))
private val AnimeLight = Color(0xFFC4B5FD)

/** Fullscreen toggle button overlaid at the bottom-right of the video (visible in both modes). */
@Composable
fun PlayerFullscreenButton(fullscreen: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(12.dp)
            .size(42.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Episode list shown below the mpv video in portrait mode. Anchored to the current episode so the
 * next episodes sit under it; tapping an episode switches playback in place.
 */
@Composable
fun PlayerEpisodePanel(
    viewModel: PlayerViewModel,
    onSwitchEpisode: (Long) -> Unit,
) {
    val playlist by viewModel.currentPlaylist.collectAsStateWithLifecycle()
    val current by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val ordered = remember(playlist) { playlist.sortedBy { it.episode_number } }

    val listState = rememberLazyListState()
    LaunchedEffect(current?.id, ordered.size) {
        val idx = ordered.indexOfFirst { it.id == current?.id }
        if (idx >= 0) listState.scrollToItem(idx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KotoriColors.bgPlayer),
    ) {
        Text(
            text = "DANH SÁCH TẬP",
            color = KotoriColors.textMuted,
            fontFamily = BeVietnamProFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 6.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(ordered, key = { _, e -> e.id ?: e.hashCode().toLong() }) { _, episode ->
                EpisodeRow(
                    episode = episode,
                    active = episode.id == current?.id,
                    onClick = { episode.id?.let(onSwitchEpisode) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, active: Boolean, onClick: () -> Unit) {
    val progress = if (episode.total_seconds > 0L && episode.last_second_seen in 1 until episode.total_seconds) {
        (episode.last_second_seen.toFloat() / episode.total_seconds).coerceIn(0f, 1f)
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
                Text(
                    text = "T${formatEpisodeNumber(episode.episode_number.toDouble())}",
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
            if (active) {
                Icon(Icons.Filled.PlayCircle, null, tint = Color(0xFFF472B6), modifier = Modifier.size(20.dp))
            } else if (episode.seen) {
                Icon(Icons.Filled.CheckCircle, null, tint = KotoriColors.textMuted, modifier = Modifier.size(20.dp))
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
