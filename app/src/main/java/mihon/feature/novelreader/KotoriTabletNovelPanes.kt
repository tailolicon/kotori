package mihon.feature.novelreader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriTabletShapes
import eu.kanade.presentation.theme.kotori.KotoriTabletTokens
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import mihon.feature.novelreader.NovelReaderPreferences.NovelFont
import mihon.feature.novelreader.NovelReaderPreferences.NovelTheme
import tachiyomi.domain.chapter.model.Chapter

private val TEAL = Color(0xFF0D9488)
private val TEAL_GRADIENT = Brush.linearGradient(listOf(Color(0xFF14B8A6), Color(0xFF5EEAD4)))

/**
 * T5 · Novel reader — the 268 dp chapter pane on the paper tone, with the search field,
 * the chapter list carrying read / active / new states and the progress card.
 */
@Composable
fun KotoriNovelChapterPane(
    title: String,
    chapters: List<Chapter>,
    currentChapterId: Long?,
    progressPercent: Int,
    onNavigateUp: () -> Unit,
    onSelectChapter: (Chapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(chapters, query) {
        if (query.isBlank()) chapters else chapters.filter { it.name.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentChapterId, filtered.size) {
        val index = filtered.indexOfFirst { it.id == currentChapterId }
        if (index >= 0) listState.scrollToItem(index)
    }

    Column(
        modifier = modifier
            .width(KotoriTabletTokens.novelChapterPane)
            .fillMaxHeight()
            .background(Color(0xFFEADFC7))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color(0xFF4B443A),
                modifier = Modifier
                    .size(21.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNavigateUp,
                    ),
            )
            Text(
                text = title,
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color(0xFF2E2A22),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(Color(0x99FFFFFF))
                .border(1.dp, Color(0x1F2E2A22), RoundedCornerShape(15.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = Color(0xFF8A8171),
                modifier = Modifier.size(17.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Tìm chương…",
                        fontFamily = BeVietnamProFamily,
                        fontSize = 12.sp,
                        color = Color(0xFF8A8171),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = BeVietnamProFamily,
                        fontSize = 12.sp,
                        color = Color(0xFF2E2A22),
                    ),
                    cursorBrush = SolidColor(TEAL),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            text = "${chapters.size} CHƯƠNG",
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.5.sp,
            letterSpacing = 0.16.em,
            color = TEAL,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(filtered, key = { _, chapter -> chapter.id }) { _, chapter ->
                val active = chapter.id == currentChapterId
                val read = chapter.read
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KotoriTabletShapes.chapterRow)
                        .then(
                            when {
                                active -> Modifier
                                    .background(TEAL.copy(alpha = 0.14f))
                                    .border(1.dp, TEAL.copy(alpha = 0.45f), KotoriTabletShapes.chapterRow)
                                !read -> Modifier
                                    .background(Color(0x8CFFFFFF))
                                    .border(1.dp, Color(0x1F2E2A22), KotoriTabletShapes.chapterRow)
                                else -> Modifier
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectChapter(chapter) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = "Ch. ${formatChapterNumberShort(chapter.chapterNumber)}",
                        fontFamily = UnboundedFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = if (active) TEAL else Color(0xFFB0A896),
                        modifier = Modifier.width(40.dp),
                    )
                    Text(
                        text = chapter.name,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = if (active || !read) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        color = when {
                            active -> Color(0xFF1F3B37)
                            read -> Color(0xFF8A8171)
                            else -> Color(0xFF2E2A22)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        active -> Icon(
                            imageVector = Icons.Filled.AutoStories,
                            contentDescription = null,
                            tint = TEAL,
                            modifier = Modifier.size(15.dp),
                        )
                        read -> Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = TEAL,
                            modifier = Modifier.size(15.dp),
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            tint = Color(0xFF8A8171),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(TEAL.copy(alpha = 0.1f))
                .border(1.dp, TEAL.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = "TIẾN ĐỘ",
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.1.em,
                color = TEAL,
            )
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x242E2A22)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((progressPercent / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(TEAL_GRADIENT),
                )
            }
            val index = chapters.indexOfFirst { it.id == currentChapterId }
            Text(
                text = "Ch. ${(index + 1).coerceAtLeast(1)}/${chapters.size} · $progressPercent%",
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = Color(0xFF6E6558),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * T5 · the bar under the reading column: `‹ Ch. 11`, the chapter progress, `Ch. 13 ›`,
 * on the page's own paper rather than the dark reader chrome.
 */
@Composable
fun KotoriNovelChapterBar(
    previousLabel: String?,
    nextLabel: String?,
    progress: Float,
    ink: Color,
    accent: Color,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 54.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (previousLabel != null) {
            KotoriChapterStep(
                label = previousLabel,
                ink = ink,
                accent = accent,
                leading = true,
                onClick = onPrevious,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ink.copy(alpha = 0.13f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(TEAL_GRADIENT),
            )
        }
        if (nextLabel != null) {
            KotoriChapterStep(
                label = nextLabel,
                ink = ink,
                accent = accent,
                leading = false,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun KotoriChapterStep(
    label: String,
    ink: Color,
    accent: Color,
    leading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x8CFFFFFF))
            .border(1.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading) {
            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!leading) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * T5 · Novel reader — the 216 dp dark settings sidebar: size slider, font list, paper
 * swatches, the layout switch and the night toggle.
 */
@Composable
fun KotoriNovelSettingsSidebar(
    preferences: NovelReaderPreferences,
    fontSize: Int,
    font: NovelFont,
    theme: NovelTheme,
    columnCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(KotoriTabletTokens.novelSettingsPane)
            .fillMaxHeight()
            .background(Color(0xF714101C))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "HIỂN THỊ",
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.5.sp,
            letterSpacing = 0.16.em,
            color = Color(0xFF5EEAD4),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "A−",
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF5EEAD4),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { preferences.fontSize.set((fontSize - 1).coerceAtLeast(12)) },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x24FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(((fontSize - 12) / 16f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(TEAL_GRADIENT),
                    )
                }
                Text(
                    text = "A+",
                    fontFamily = BeVietnamProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF5EEAD4),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { preferences.fontSize.set((fontSize + 1).coerceAtMost(28)) },
                )
            }
            Text(
                text = "Cỡ chữ ${fontSize}px · ${font.label}",
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            NovelFont.entries.forEach { entry ->
                val selected = entry == font
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (selected) {
                                Modifier.background(TEAL_GRADIENT)
                            } else {
                                Modifier
                                    .background(Color(0x12FFFFFF))
                                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { preferences.fontFamily.set(entry) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.label,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (selected) Color(0xFF0B1512) else KotoriColors.textSecondary,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Nền giấy",
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelTheme.entries.forEach { entry ->
                    val selected = entry == theme
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(entry.paper().background)
                            .border(
                                2.dp,
                                if (selected) Color(0xFF5EEAD4) else Color(0x40FFFFFF),
                                CircleShape,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { preferences.theme.set(entry) },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Bố cục",
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(2 to "2 cột", 1 to "1 cột").forEach { (count, label) ->
                    val selected = count == columnCount
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .then(
                                if (selected) {
                                    Modifier.background(TEAL_GRADIENT)
                                } else {
                                    Modifier
                                        .background(Color(0x12FFFFFF))
                                        .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(13.dp))
                                },
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { preferences.columnCount.set(count) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontFamily = BeVietnamProFamily,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            color = if (selected) Color(0xFF0B1512) else KotoriColors.textSecondary,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x0FFFFFFF))
                .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    preferences.theme.set(
                        if (theme == NovelTheme.DARK || theme == NovelTheme.BLACK) {
                            NovelTheme.SEPIA
                        } else {
                            NovelTheme.DARK
                        },
                    )
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = null,
                tint = Color(0xFF5EEAD4),
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "Chế độ đêm",
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = KotoriColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            val night = theme == NovelTheme.DARK || theme == NovelTheme.BLACK
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (night) Modifier.background(TEAL_GRADIENT) else Modifier.background(Color(0x24FFFFFF)),
                    ),
                contentAlignment = if (night) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (night) Color.White else KotoriColors.textMuted),
                )
            }
        }
    }
}

private fun formatChapterNumberShort(number: Double): String {
    return if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
}
