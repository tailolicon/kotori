package eu.kanade.tachiyomi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriPanelLabel
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * T3 · Player — the episode drawer docked permanently to the right of the video on
 * tablet. The current episode is violet-highlighted and the auto-next card is pinned
 * to the bottom of the panel.
 */
@Composable
fun KotoriTabletEpisodeDrawer(
    viewModel: PlayerViewModel,
    onSwitchEpisode: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = AnimeAccent
    val playlist by viewModel.currentPlaylist.collectAsStateWithLifecycle()
    val current by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val anime by viewModel.currentAnime.collectAsStateWithLifecycle()
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val autoPlay by playerPreferences.autoplayEnabled().collectAsState()

    val ordered = remember(playlist) { playlist.sortedBy { it.episode_number } }
    val listState = rememberLazyListState()
    LaunchedEffect(current?.id, ordered.size) {
        val index = ordered.indexOfFirst { it.id == current?.id }
        if (index >= 0) listState.scrollToItem(index)
    }

    Column(
        modifier = modifier
            .width(322.dp)
            .fillMaxHeight()
            .clip(KotoriTabletShapes.playerDrawer)
            .background(Color(0xB8100C18))
            .border(1.dp, Color(0x1FFFFFFF), KotoriTabletShapes.playerDrawer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KotoriPanelLabel(
                text = "DANH SÁCH TẬP · ${ordered.size}",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = KotoriColors.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(ordered, key = { it.id ?: it.hashCode().toLong() }) { episode ->
                val active = episode.id == current?.id
                val progress = if (
                    episode.total_seconds > 0L &&
                    episode.last_second_seen in 1 until episode.total_seconds
                ) {
                    (episode.last_second_seen.toFloat() / episode.total_seconds).coerceIn(0f, 1f)
                } else {
                    null
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KotoriTabletShapes.listRow)
                        .background(
                            when {
                                active -> accent.start.copy(alpha = 0.18f)
                                else -> Color(0x0DFFFFFF)
                            },
                        )
                        .border(
                            1.dp,
                            if (active) accent.light.copy(alpha = 0.55f) else Color(0x17FFFFFF),
                            KotoriTabletShapes.listRow,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { episode.id?.let(onSwitchEpisode) }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(74.dp)
                            .height(44.dp)
                            .clip(KotoriTabletShapes.panelThumb)
                            .background(Color(0xFF1B1629)),
                    ) {
                        AsyncImage(
                            model = anime?.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (progress != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(Color(0x66000000)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress)
                                        .fillMaxHeight()
                                        .background(accent.ctaGradient),
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "T${formatEpisodeNumberShort(episode.episode_number)} · ${episode.name}",
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = KotoriColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when {
                                active -> "đang phát" + (progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                                episode.seen -> "đã xem"
                                progress != null -> "xem dở ${(progress * 100).toInt()}%"
                                else -> "chưa xem"
                            },
                            fontFamily = BeVietnamProFamily,
                            fontSize = 10.sp,
                            color = KotoriColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        imageVector = when {
                            active -> Icons.Filled.GraphicEq
                            episode.seen -> Icons.Filled.CheckCircle
                            else -> Icons.Filled.Download
                        },
                        contentDescription = null,
                        tint = when {
                            active -> accent.light
                            episode.seen -> KotoriColors.textFaint
                            else -> KotoriColors.textMuted
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(accent.start.copy(alpha = 0.16f))
                .border(1.dp, accent.light.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { playerPreferences.autoplayEnabled().set(!autoPlay) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = null,
                tint = accent.light,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Tự phát tập kế",
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = KotoriColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (autoPlay) accent.gradient else SolidColor(Color(0x1AFFFFFF)))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (autoPlay) "Bật" else "Tắt",
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    color = if (autoPlay) accent.onAccent else KotoriColors.textPrimary,
                )
            }
        }
    }
}

private fun formatEpisodeNumberShort(number: Float): String {
    return if (number % 1f == 0f) number.toInt().toString() else number.toString()
}
