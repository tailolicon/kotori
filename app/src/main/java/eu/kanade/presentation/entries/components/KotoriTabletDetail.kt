package eu.kanade.presentation.entries.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import eu.kanade.presentation.components.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriAccent
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** A status chip over the key visual: `Đang chiếu`, `2026`, `12 tập · 24 phút`. */
@Immutable
data class KotoriDetailChip(
    val label: String,
    val highlighted: Boolean = false,
)

/** One item of the ⋯ overflow on the T2 action cluster. */
@Immutable
data class KotoriDetailMenuAction(
    val label: String,
    val onClick: () -> Unit,
)

/** One row of the two-column episode/chapter grid on the right pane. */
@Immutable
data class KotoriTabletDetailItem(
    val key: String,
    val badge: String,
    val title: String,
    val subtitle: String,
    val thumbData: Any?,
    val progress: Float?,
    val stateIcon: ImageVector?,
    val stateTint: Color,
    val dimmed: Boolean,
    val highlighted: Boolean,
    val selected: Boolean,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    val onStateClick: (() -> Unit)?,
)

/**
 * T2 · Chi tiết — tablet composition.
 *
 * Left 520 dp full-height key visual with a horizontal scrim into the background;
 * every piece of metadata, the synopsis and the action cluster overlay it. The right
 * pane is the tab row plus a two-column episode/chapter grid filling the canvas.
 */
@Composable
fun KotoriTabletDetailLayout(
    accent: KotoriAccent,
    title: String,
    coverData: Any?,
    chips: List<KotoriDetailChip>,
    metaLine: String,
    genres: List<String>,
    onGenreClick: ((String) -> Unit)?,
    rating: String?,
    trackerLine: String?,
    description: String?,
    favorite: Boolean,
    trackingCount: Int,
    ctaLabel: String,
    ctaIcon: ImageVector,
    onNavigateUp: () -> Unit,
    onCta: () -> Unit,
    onFavorite: () -> Unit,
    onTracking: () -> Unit,
    onDownload: (() -> Unit)?,
    onMore: () -> Unit,
    moreActions: List<KotoriDetailMenuAction> = emptyList(),
    downloadMenu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)? = null,
    onCoverClick: () -> Unit,
    tabs: List<String>,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    onFilter: () -> Unit,
    quickDownloadLabel: String?,
    onQuickDownload: (() -> Unit)?,
    selectionCount: Int = 0,
    onSelectAll: (() -> Unit)? = null,
    onInvertSelection: (() -> Unit)? = null,
    items: List<KotoriTabletDetailItem>,
    modifier: Modifier = Modifier,
    listHeader: (@Composable () -> Unit)? = null,
    tabContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxSize()) {
        // ── Left pane: key visual + everything overlaid on it ──────────────────
        Box(
            modifier = Modifier
                .width(KotoriTabletTokens.detailArtWidth)
                .fillMaxHeight(),
        ) {
            AsyncImage(
                model = coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCoverClick,
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color(0x2614101F),
                            0.55f to Color(0x8C14101F),
                            1f to KotoriColors.bgBase,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 20.dp, top = 18.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0x8C14101F))
                    .border(1.dp, Color(0x24FFFFFF), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNavigateUp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = KotoriColors.textPrimary,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 34.dp, end = 26.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (chips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        chips.forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(11.dp))
                                    .then(
                                        if (chip.highlighted) {
                                            Modifier.background(accent.gradient)
                                        } else {
                                            Modifier.background(Color(0x21FFFFFF))
                                        },
                                    )
                                    .padding(horizontal = 11.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = chip.label,
                                    fontFamily = BeVietnamProFamily,
                                    fontWeight = if (chip.highlighted) FontWeight.Bold else FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = if (chip.highlighted) accent.onAccent else Color(0xFFE9E4F7),
                                )
                            }
                        }
                    }
                }
                Text(
                    text = title,
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp,
                    lineHeight = 40.sp,
                    color = KotoriColors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Credits and genres share one line: the genres are the same words the metadata
                // line always showed, only now each one is its own link into the source's search.
                if (metaLine.isNotBlank() || genres.isNotEmpty()) {
                    val genreStyle = remember(accent) {
                        TextLinkStyles(
                            style = SpanStyle(color = accent.light, fontWeight = FontWeight.SemiBold),
                        )
                    }
                    Text(
                        text = buildAnnotatedString {
                            if (metaLine.isNotBlank()) {
                                append(metaLine)
                                if (genres.isNotEmpty()) append(" · ")
                            }
                            genres.forEachIndexed { index, genre ->
                                if (index > 0) append(", ")
                                if (onGenreClick == null) {
                                    append(genre)
                                } else {
                                    val link = LinkAnnotation.Clickable(
                                        tag = genre,
                                        styles = genreStyle,
                                    ) { onGenreClick(genre) }
                                    withLink(link) { append(genre) }
                                }
                            }
                        },
                        fontFamily = BeVietnamProFamily,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Color(0xFFC9C1E2),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rating != null || trackerLine != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (rating != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = KotoriColors.star,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    text = rating,
                                    fontFamily = BeVietnamProFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = KotoriColors.star,
                                )
                            }
                        }
                        if (trackerLine != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sync,
                                    contentDescription = null,
                                    tint = KotoriColors.success,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = trackerLine,
                                    fontFamily = BeVietnamProFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = KotoriColors.success,
                                )
                            }
                        }
                    }
                }
                if (!description.isNullOrBlank()) {
                    var expanded by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.width(440.dp)) {
                        Text(
                            text = description,
                            fontFamily = BeVietnamProFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 20.6.sp,
                            color = KotoriColors.textSecondary,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (expanded) "Thu gọn" else "Xem thêm",
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            color = accent.light,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { expanded = !expanded },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(KotoriTabletShapes.detailCta)
                            .background(accent.ctaGradient)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onCta,
                            )
                            .padding(horizontal = 26.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ctaIcon,
                            contentDescription = null,
                            tint = accent.onAccent,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = ctaLabel,
                            fontFamily = BeVietnamProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = accent.onAccent,
                        )
                    }
                    KotoriDetailCircleAction(
                        icon = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        tint = if (favorite) Color(0xFFF472B6) else Color(0xFFCFC7E8),
                        onClick = onFavorite,
                    )
                    KotoriDetailCircleAction(
                        icon = Icons.Filled.Sync,
                        tint = accent.light,
                        onClick = onTracking,
                        badge = trackingCount.takeIf { it > 0 },
                        badgeBrush = accent.gradient,
                    )
                    if (downloadMenu != null) {
                        Box {
                            var downloadOpen by remember { mutableStateOf(false) }
                            KotoriDetailCircleAction(
                                icon = Icons.Filled.Download,
                                tint = accent.light,
                                onClick = { downloadOpen = true },
                            )
                            downloadMenu(downloadOpen) { downloadOpen = false }
                        }
                    } else if (onDownload != null) {
                        KotoriDetailCircleAction(
                            icon = Icons.Filled.Download,
                            tint = accent.light,
                            onClick = onDownload,
                        )
                    }
                    Box {
                        var moreOpen by remember { mutableStateOf(false) }
                        KotoriDetailCircleAction(
                            icon = Icons.Filled.MoreHoriz,
                            tint = Color(0xFFCFC7E8),
                            onClick = {
                                if (moreActions.isEmpty()) onMore() else moreOpen = true
                            },
                        )
                        if (moreActions.isNotEmpty()) {
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                moreActions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        onClick = {
                                            moreOpen = false
                                            action.onClick()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Right pane: tabs + two-column item grid ────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 10.dp, end = 26.dp, top = 22.dp, bottom = 22.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 11.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = index == activeTab
                    Column(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectTab(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            fontFamily = BeVietnamProFamily,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 14.5.sp,
                            color = if (selected) KotoriColors.textPrimary else KotoriColors.textMuted,
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 9.dp)
                                .height(3.dp)
                                .width(if (selected) 52.dp else 0.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) accent.ctaGradient else Brush.linearGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                // Selection borrows this row rather than pushing a second bar into the grid:
                // the quick-download chip and the filter step aside while a selection is live,
                // the same swap the phone toolbar makes.
                if (selectionCount > 0 && onSelectAll != null && onInvertSelection != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectionCount.toString(),
                            fontFamily = UnboundedFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = accent.light,
                        )
                        KotoriHeaderAction(
                            icon = Icons.Outlined.SelectAll,
                            contentDescription = stringResource(MR.strings.action_select_all),
                            onClick = onSelectAll,
                        )
                        KotoriHeaderAction(
                            icon = Icons.Outlined.FlipToBack,
                            contentDescription = stringResource(MR.strings.action_select_inverse),
                            onClick = onInvertSelection,
                        )
                    }
                } else {
                    if (quickDownloadLabel != null && onQuickDownload != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .background(Color(0x0FFFFFFF))
                                .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(15.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onQuickDownload,
                                )
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = accent.light,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = quickDownloadLabel,
                                fontFamily = BeVietnamProFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                color = KotoriColors.textPrimary,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = null,
                        tint = KotoriColors.textMuted,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .size(21.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onFilter,
                            ),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x14FFFFFF)),
            )
            if (tabContent != null) {
                Column(modifier = Modifier.weight(1f)) { tabContent() }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    if (listHeader != null) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                            listHeader()
                        }
                    }
                    items(
                        items = items,
                        key = { it.key },
                        contentType = { "kotori_tablet_detail_item" },
                    ) { item ->
                        KotoriTabletDetailRow(item = item, accent = accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun KotoriDetailCircleAction(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    badge: Int? = null,
    badgeBrush: Brush? = null,
) {
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x26FFFFFF), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        if (badge != null && badgeBrush != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(badgeBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$badge",
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun KotoriTabletDetailRow(
    item: KotoriTabletDetailItem,
    accent: KotoriAccent,
) {
    val shape = KotoriShapes.row
    val borderColor = when {
        item.selected -> accent.light
        item.highlighted -> Color(0x73F472B6)
        else -> Color(0x17FFFFFF)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.dimmed) 0.55f else 1f)
            .clip(shape)
            .background(if (item.selected) accent.start.copy(alpha = 0.16f) else Color(0x0DFFFFFF))
            .border(1.dp, borderColor, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = item.onClick,
                onLongClick = item.onLongClick,
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .height(62.dp)
                .clip(KotoriTabletShapes.episodeThumb)
                .background(Color(0xFF1B1629)),
        ) {
            AsyncImage(
                model = item.thumbData,
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
                            1f to Color(0x99000000),
                        ),
                    ),
            )
            Text(
                text = item.badge,
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 6.dp),
            )
            if (item.progress != null) {
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
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = KotoriColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (item.stateIcon != null) {
            Icon(
                imageVector = item.stateIcon,
                contentDescription = null,
                tint = item.stateTint,
                modifier = Modifier
                    .size(21.dp)
                    .then(
                        if (item.onStateClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = item.onStateClick,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
