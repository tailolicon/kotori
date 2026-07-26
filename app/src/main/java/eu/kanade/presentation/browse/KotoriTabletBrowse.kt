package eu.kanade.presentation.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriInlineSearchField
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.UnboundedFamily

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
    activeFilter: Int,
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
                                fontSize = 11.5.sp,
                                color = if (selected) accent.onAccent else KotoriColors.textMuted,
                                maxLines = 1,
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
                        val selected = index == activeFilter
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
