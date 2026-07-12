package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientCircleButton
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.theme.kotori.accent
import eu.kanade.presentation.theme.kotori.glass
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

/** Gradient wordmark `kotori ✦` (Unbounded 800). */
@Composable
fun KotoriWordmark(modifier: Modifier = Modifier) {
    val accent = KotoriTheme.accent
    Text(
        text = "kotori ✦",
        style = TextStyle(
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 17.sp,
            brush = Brush.linearGradient(listOf(accent.end, accent.start)),
        ),
        modifier = modifier,
    )
}

private data class ModeSegment(
    val type: MediaType,
    val label: String,
    val icon: ImageVector,
)

private val modeSegments = listOf(
    ModeSegment(MediaType.MANGA, "Manga", Icons.AutoMirrored.Filled.MenuBook),
    ModeSegment(MediaType.ANIME, "Anime", Icons.Filled.SmartDisplay),
    ModeSegment(MediaType.NOVEL, "Novel", Icons.Filled.Article),
)

/**
 * 3-segment glass mode switcher `Manga · Anime · Novel`.
 * Active segment: mode gradient, kotori corner pointing at content, glow.
 */
@Composable
fun KotoriModeSwitcher(
    active: MediaType,
    onSelect: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = RoundedCornerShape(22.dp), elevated = true)
            .padding(5.dp),
    ) {
        modeSegments.forEach { segment ->
            val selected = segment.type == active
            val accent = segment.type.accent
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (selected) {
                            Modifier
                                .shadow(
                                    elevation = 6.dp,
                                    shape = KotoriShapes.segmentActive,
                                    ambientColor = accent.start.copy(alpha = 0.45f),
                                    spotColor = accent.start.copy(alpha = 0.45f),
                                )
                                .clip(KotoriShapes.segmentActive)
                                .background(accent.gradient)
                        } else {
                            Modifier.clip(KotoriShapes.segment)
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(segment.type) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = segment.icon,
                    contentDescription = null,
                    tint = if (selected) accent.onAccent else KotoriColors.textMuted,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = segment.label,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = if (selected) accent.onAccent else KotoriColors.textMuted,
                )
            }
        }
    }
}

/**
 * Resume hero card: `ĐANG ĐỌC DỞ` / `ĐANG XEM DỞ` — thumb, title, meta,
 * gradient progress bar and a circular gradient play FAB.
 */
@Composable
fun KotoriResumeHeroCard(
    mode: MediaType,
    title: String,
    meta: String,
    progress: Float,
    coverData: MangaCoverModel,
    onClick: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = mode.accent
    val label = when (mode) {
        MediaType.ANIME -> "ĐANG XEM DỞ"
        else -> "ĐANG ĐỌC DỞ"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = KotoriShapes.hero, elevated = true)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverData,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(118.dp)
                .height(76.dp)
                .clip(KotoriShapes.thumb),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.1.em,
                color = accent.light,
            )
            Text(
                text = title,
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                text = meta,
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = KotoriColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1FFFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent.gradient),
                )
            }
        }
        GradientCircleButton(
            icon = when (mode) {
                MediaType.ANIME -> Icons.Filled.PlayArrow
                else -> Icons.Filled.AutoStories
            },
            contentDescription = label,
            onClick = onResume,
            accent = accent,
        )
    }
}

/**
 * Category chips row with counts: active = gradient fill, inactive = glass.
 */
@Composable
fun KotoriCategoryChips(
    categories: List<String>,
    counts: List<Int?>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
    ) {
        itemsIndexed(categories) { index, name ->
            val selected = index == activeIndex
            Row(
                modifier = Modifier
                    .then(
                        if (selected) {
                            Modifier
                                .clip(KotoriShapes.pill)
                                .background(accent.gradient)
                        } else {
                            Modifier.glass(shape = KotoriShapes.pill, elevated = true)
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                    maxLines = 1,
                )
                counts.getOrNull(index)?.let { count ->
                    Text(
                        text = "$count",
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = (if (selected) accent.onAccent else KotoriColors.textSecondary)
                            .copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
