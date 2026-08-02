package eu.kanade.presentation.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.runtime.remember
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriChip
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.theme.kotori.KotoriInlineSearchField
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.KotoriTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * T6 · one compact source row of the 300 dp column: a 38 dp tile, the name, the
 * `VI · Anime` sub-line and the pin. Deliberately no `Mới` / `Hot` chips — the column is
 * a picker, and the mock keeps those on the phone's full-width rows.
 *
 * The tile shows the source's real icon when it has one and falls back to the mock's
 * gradient monogram otherwise, rather than throwing a real icon away for a placeholder.
 */
@Composable
fun KotoriCompactSourceRow(
    name: String,
    subtitle: String,
    pinned: Boolean,
    selected: Boolean,
    supportsLatest: Boolean,
    onClick: () -> Unit,
    onClickLatest: () -> Unit,
    onClickPopular: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val accent = KotoriTheme.accent
    val shape = KotoriTabletShapes.listRow
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(accent.start.copy(alpha = 0.16f))
                        .border(1.dp, accent.light.copy(alpha = 0.5f), shape)
                } else {
                    Modifier
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, Color(0x17FFFFFF), shape)
                },
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(KotoriTabletShapes.sourceTile),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    icon()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(accent.gradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            fontFamily = UnboundedFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    color = KotoriColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontFamily = BeVietnamProFamily,
                        fontSize = 10.sp,
                        color = KotoriColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = null,
                tint = if (pinned) accent.light else KotoriColors.textFaint,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTogglePin,
                    ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            if (supportsLatest) {
                KotoriChip(
                    text = stringResource(MR.strings.latest),
                    selected = false,
                    onClick = onClickLatest,
                )
            }
            KotoriChip(
                text = stringResource(MR.strings.popular),
                selected = true,
                onClick = onClickPopular,
            )
        }
    }
}

/**
 * T6 · one per-source shelf in the results pane: the source name, its type tag, the
 * result count, `Xem hết ›`, and a horizontal row of 118×164 covers.
 */
@Composable
fun KotoriSearchShelf(
    sourceName: String,
    typeTag: String,
    countLabel: String?,
    onClickSource: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val accent = KotoriTheme.accent
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClickSource,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = sourceName,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.start.copy(alpha = 0.14f))
                    .border(1.dp, accent.light.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = typeTag,
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.1.em,
                    color = accent.light,
                )
            }
            if (countLabel != null) {
                Text(
                    text = countLabel,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 11.sp,
                    color = KotoriColors.textFaint,
                )
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "Xem hết ›",
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = accent.light,
            )
        }
        content()
    }
}

/** T6 · one 118×164 cover in a results shelf, with the rotating clipped corner. */
@Composable
fun KotoriShelfCover(
    title: String,
    coverData: Any?,
    inLibrary: Boolean,
    index: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = shelfCoverShape(index)
    Column(modifier = modifier.width(118.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
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
                model = coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (inLibrary) 0.72f else 1f,
                modifier = Modifier.fillMaxSize(),
            )
            if (inLibrary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xB814101F))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(9.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "✓ THƯ VIỆN",
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.5.sp,
                        letterSpacing = 0.06.em,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = title,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = KotoriColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

/** Glass skeleton in the shelf's own geometry, while a source is still searching. */
@Composable
fun KotoriShelfShimmer(count: Int = 6) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { index ->
            Column(modifier = Modifier.width(118.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(164.dp)
                        .clip(shelfCoverShape(index))
                        .background(Color(0x0FFFFFFF)),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .fillMaxWidth(0.7f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x0FFFFFFF)),
                )
            }
        }
    }
}

/** Faint one-liner used when a source returned nothing or failed. */
@Composable
fun KotoriShelfMessage(text: String) {
    Text(
        text = text,
        fontFamily = BeVietnamProFamily,
        fontSize = 11.5.sp,
        color = KotoriColors.textFaint,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** Rotating clipped corner across a shelf: 18/18/18/6 walking round the box. */
private fun shelfCoverShape(index: Int): RoundedCornerShape {
    val big = 18.dp
    val small = 6.dp
    return when (index % 4) {
        0 -> RoundedCornerShape(big, big, big, small)
        1 -> RoundedCornerShape(big, big, small, big)
        2 -> RoundedCornerShape(small, big, big, big)
        else -> RoundedCornerShape(big, small, big, big)
    }
}

/**
 * T6 · Duyệt & tìm toàn cục — tablet composition.
 *
 * Rail (owned by HomeScreen) + a 300 dp source column carrying the screen title, the
 * `Nguồn / Tiện ích / Di dời` segmented control and the pinned source list, and a
 * results pane with the gradient-bordered global search, its filter chips and the
 * per-source shelves.
 */
@Composable
fun KotoriTabletBrowseLayout(
    title: String,
    tabLabels: List<String>,
    tabBadges: List<Int?>,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    filterLabels: List<String>,
    // `Mọi nguồn` / `Đã ghim` pick the source set and `Có trong thư viện` hides empty
    // sources — they are independent switches in the model, not one radio group, so more
    // than one can be lit at a time.
    activeFilters: Set<Int>,
    onSelectFilter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sourceColumn: @Composable () -> Unit,
    results: @Composable () -> Unit,
) {
    val accent = KotoriTheme.accent
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(KotoriTabletTokens.browseSourcePane)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = KotoriColors.textPrimary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(KotoriTabletShapes.inlineSearch)
                    .background(Color(0x0FFFFFFF))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp))
                    .padding(4.dp),
            ) {
                tabLabels.forEachIndexed { index, label ->
                    val selected = index == activeTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (selected) {
                                    Modifier
                                        .clip(KotoriTabletShapes.segmentActiveSmall)
                                        .background(accent.gradient)
                                } else {
                                    Modifier.clip(RoundedCornerShape(15.dp))
                                },
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelectTab(index) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                fontFamily = BeVietnamProFamily,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = if (selected) accent.onAccent else KotoriColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val badge = tabBadges.getOrNull(index)
                            if (badge != null && badge > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) Color(0x33FFFFFF) else KotoriColors.glassBgPressed,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "$badge",
                                        fontFamily = BeVietnamProFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) { sourceColumn() }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Color(0x12FFFFFF)),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KotoriInlineSearchField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = "Tìm trên mọi nguồn…",
                    leadingIcon = Icons.Filled.TravelExplore,
                    gradientBorder = true,
                    onClear = onClearSearch,
                    onSearch = onSearch,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filterLabels.forEachIndexed { index, label ->
                        val selected = index in activeFilters
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .then(
                                    if (selected) {
                                        Modifier.background(accent.gradient)
                                    } else {
                                        Modifier
                                            .background(Color(0x0FFFFFFF))
                                            .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(15.dp))
                                    },
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelectFilter(index) }
                                .padding(horizontal = 15.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = label,
                                fontFamily = BeVietnamProFamily,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                color = if (selected) accent.onAccent else KotoriColors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 18.dp),
            ) {
                results()
            }
        }
    }
}
