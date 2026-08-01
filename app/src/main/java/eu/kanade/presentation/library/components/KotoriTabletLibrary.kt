package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientCircleButton
import eu.kanade.presentation.theme.kotori.KotoriCircleAction
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriInlineSearchField
import eu.kanade.presentation.theme.kotori.KotoriPanelDivider
import eu.kanade.presentation.theme.kotori.KotoriPanelLabel
import eu.kanade.presentation.theme.kotori.KotoriProgressBar
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.theme.kotori.accent

/** One cover tile of the 6-column tablet library grid. */
@Immutable
data class KotoriTabletLibraryTile(
    val id: Long,
    val title: String,
    val statusLine: String,
    val coverData: Any?,
    val unreadCount: Long,
    val downloaded: Boolean,
    val selected: Boolean,
)

/** `ĐANG XEM DỞ` / `ĐANG ĐỌC DỞ` hero card at the top of the right column. */
@Immutable
data class KotoriTabletHero(
    val title: String,
    val meta: String,
    val progress: Float,
    val coverData: Any?,
    val onClick: () -> Unit,
    val onResume: () -> Unit,
)

/** One row of the persistent `TIẾP TỤC` panel — mixes all three content types. */
@Immutable
data class KotoriTabletResumeItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val coverData: Any?,
    val progress: Float,
    val mode: MediaType,
    val onClick: () -> Unit,
)

/** One row of the `HÔM NAY LÊN SÓNG` panel. */
@Immutable
data class KotoriTabletAiringItem(
    val key: String,
    val time: String,
    val title: String,
    val notify: Boolean,
    val onToggleNotify: () -> Unit,
    val onClick: () -> Unit,
)

/**
 * T1 · Thư viện — tablet composition.
 *
 * Rail (owned by HomeScreen) + top bar (mode switcher · inline search · tune/sync/grid)
 * + category chips; a 6-column cover grid filling the height and a 316 dp right column
 * holding the resume hero and the `TIẾP TỤC` / `HÔM NAY LÊN SÓNG` panel.
 */
@Composable
fun KotoriTabletLibraryLayout(
    activeMode: MediaType,
    onSelectMode: (MediaType) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    filtersActive: Boolean,
    onRefresh: () -> Unit,
    onCycleDisplayMode: () -> Unit,
    categories: List<String>,
    categoryCounts: List<Int?>,
    activeCategoryIndex: Int,
    onSelectCategory: (Int) -> Unit,
    title: String,
    subtitle: String,
    tiles: List<KotoriTabletLibraryTile>,
    onClickTile: (Long) -> Unit,
    onLongClickTile: (Long) -> Unit,
    hero: KotoriTabletHero?,
    resumeItems: List<KotoriTabletResumeItem>,
    airingItems: List<KotoriTabletAiringItem>,
    modifier: Modifier = Modifier,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    val accent = activeMode.accent
    Column(modifier = modifier.fillMaxSize()) {
        // Top bar: mode switcher · persistent inline search · tune / sync / grid.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = KotoriTabletTokens.screenPadding, end = KotoriTabletTokens.screenPadding, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            KotoriModeSwitcher(
                active = activeMode,
                onSelect = onSelectMode,
                modifier = Modifier.width(360.dp),
                compact = false,
            )
            KotoriInlineSearchField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = "Tìm trong thư viện…",
                onClear = onClearSearch,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                KotoriCircleAction(
                    icon = Icons.Filled.Tune,
                    contentDescription = "Bộ lọc",
                    onClick = onOpenFilters,
                    tint = if (filtersActive) accent.light else Color(0xFFCFC7E8),
                )
                KotoriCircleAction(
                    icon = Icons.Filled.Sync,
                    contentDescription = "Cập nhật thư viện",
                    onClick = onRefresh,
                )
                KotoriCircleAction(
                    icon = Icons.Filled.GridView,
                    contentDescription = "Kiểu hiển thị",
                    onClick = onCycleDisplayMode,
                )
            }
        }

        // Category chips.
        if (categories.isNotEmpty()) {
            KotoriCategoryChips(
                categories = categories,
                counts = categoryCounts,
                activeIndex = activeCategoryIndex,
                onSelect = onSelectCategory,
                modifier = Modifier.padding(top = 16.dp),
                contentPadding = PaddingValues(horizontal = KotoriTabletTokens.screenPadding),
                spacing = 9.dp,
                horizontalPadding = 15.dp,
                verticalPadding = 8.dp,
                cornerRadius = 17.dp,
                textSize = 12.5.sp,
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = KotoriTabletTokens.screenPadding,
                    end = KotoriTabletTokens.screenPadding,
                    top = 20.dp,
                    bottom = 22.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = title,
                        fontFamily = UnboundedFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = KotoriColors.textPrimary,
                    )
                    Text(
                        text = subtitle,
                        fontFamily = BeVietnamProFamily,
                        fontSize = 12.sp,
                        color = KotoriColors.textMuted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                if (tiles.isEmpty() && emptyContent != null) {
                    Box(modifier = Modifier.fillMaxSize()) { emptyContent() }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(
                            items = tiles,
                            key = { _, it -> it.id },
                            contentType = { _, _ -> "kotori_tablet_library_tile" },
                        ) { index, tile ->
                            KotoriTabletLibraryTileItem(
                                tile = tile,
                                index = index,
                                accentLight = accent.light,
                                onClick = { onClickTile(tile.id) },
                                onLongClick = { onLongClickTile(tile.id) },
                                unreadBrush = accent.ctaGradient,
                                selectionColor = accent.start,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .width(KotoriTabletTokens.libraryAsideWidth)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (hero != null) {
                    KotoriTabletHeroCard(hero = hero, mode = activeMode)
                }
                KotoriTabletSidePanel(
                    mode = activeMode,
                    resumeItems = resumeItems,
                    airingItems = airingItems,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun KotoriTabletLibraryTileItem(
    tile: KotoriTabletLibraryTile,
    index: Int,
    accentLight: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    unreadBrush: Brush,
    selectionColor: Color,
) {
    val shape = KotoriShapes.libraryTile(index)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4.15f)
            .clip(shape)
            .background(Color(0xFF1B1629))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        AsyncImage(
            model = tile.coverData,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // radial-gradient(120% 70% at 80% 0%, rgba(255,255,255,.17), transparent 52%)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x2BFFFFFF), Color.Transparent),
                        center = Offset(0.8f * 1000f, 0f),
                        radius = 700f,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xE00B0812))),
                )
                .padding(start = 10.dp, end = 10.dp, top = 26.dp, bottom = 9.dp),
        ) {
            Text(
                text = tile.title,
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                lineHeight = 14.4.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.statusLine,
                fontFamily = BeVietnamProFamily,
                fontSize = 9.5.sp,
                color = Color(0xB3FFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (tile.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(unreadBrush)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "${tile.unreadCount}",
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = Color.White,
                )
            }
        }
        if (tile.downloaded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xA614101F)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DownloadDone,
                    contentDescription = null,
                    tint = KotoriColors.success,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (tile.selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(selectionColor.copy(alpha = 0.32f))
                    .border(2.dp, accentLight, shape),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(selectionColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun KotoriTabletHeroCard(
    hero: KotoriTabletHero,
    mode: MediaType,
    modifier: Modifier = Modifier,
) {
    val accent = mode.accent
    val label = if (mode == MediaType.ANIME) "ĐANG XEM DỞ" else "ĐANG ĐỌC DỞ"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KotoriShapes.hero)
            .background(Color(0x0DFFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), KotoriShapes.hero)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = hero.onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(158.dp),
        ) {
            AsyncImage(
                model = hero.coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to Color(0xE614101F),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            ) {
                Text(
                    text = label,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.12.em,
                    color = accent.light,
                )
                Text(
                    text = hero.title,
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = KotoriColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hero.meta,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 11.5.sp,
                    color = KotoriColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KotoriProgressBar(
                    progress = hero.progress,
                    accent = accent,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            GradientCircleButton(
                icon = if (mode == MediaType.ANIME) Icons.Filled.PlayArrow else Icons.Filled.AutoStories,
                contentDescription = label,
                onClick = hero.onResume,
                accent = accent,
                size = 46.dp,
                iconSize = 24.dp,
            )
        }
    }
}

@Composable
private fun KotoriTabletSidePanel(
    mode: MediaType,
    resumeItems: List<KotoriTabletResumeItem>,
    airingItems: List<KotoriTabletAiringItem>,
    modifier: Modifier = Modifier,
) {
    val accent = mode.accent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KotoriTabletShapes.panel)
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), KotoriTabletShapes.panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        KotoriPanelLabel(text = "TIẾP TỤC", accent = accent)
        // The resume list is the only part that may not fit, so it takes exactly what is
        // left after the airing section and scrolls. Two `fill = false` weights used to
        // share the space and let rows spill past the panel's own border.
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(resumeItems, key = { it.key }) { item ->
                KotoriTabletResumeRow(item = item)
            }
        }
        if (airingItems.isNotEmpty()) {
            KotoriPanelDivider()
            KotoriPanelLabel(text = "HÔM NAY LÊN SÓNG", accent = accent)
            airingItems.forEach { item ->
                KotoriTabletAiringRow(item = item, accentLight = accent.light)
            }
        }
    }
}

@Composable
private fun KotoriTabletResumeRow(item: KotoriTabletResumeItem) {
    val accent = item.mode.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = item.onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(36.dp)
                .clip(KotoriTabletShapes.panelThumb)
                .background(Color(0xFF1B1629)),
        ) {
            AsyncImage(
                model = item.coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x66000000)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(item.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(accent.ctaGradient),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.sp,
                color = KotoriColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = if (item.mode == MediaType.ANIME) Icons.Filled.PlayArrow else Icons.Filled.AutoStories,
            contentDescription = null,
            tint = accent.light,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun KotoriTabletAiringRow(item: KotoriTabletAiringItem, accentLight: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = item.onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.time,
            fontFamily = UnboundedFamily,
            fontSize = 10.5.sp,
            color = KotoriColors.highlightPink,
            modifier = Modifier.width(38.dp),
        )
        Text(
            text = item.title,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = KotoriColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (item.notify) Icons.Filled.Notifications else Icons.Outlined.Notifications,
            contentDescription = null,
            tint = if (item.notify) accentLight else KotoriColors.textMuted,
            modifier = Modifier
                .size(17.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = item.onToggleNotify,
                ),
        )
    }
}
