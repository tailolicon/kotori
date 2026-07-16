package mihon.feature.novelreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.LiterataFamily
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import mihon.feature.novelreader.NovelReaderPreferences.NovelFont
import mihon.feature.novelreader.NovelReaderPreferences.NovelLineSpacing
import mihon.feature.novelreader.NovelReaderPreferences.NovelTheme

data class NovelPaperTheme(
    val background: Color,
    val ink: Color,
    val accent: Color,
    val muted: Color,
)

fun NovelTheme.paper(): NovelPaperTheme = when (this) {
    NovelTheme.WHITE -> NovelPaperTheme(
        background = Color(0xFFFFFFFF),
        ink = Color(0xFF26241F),
        accent = Color(0xFF0D9488),
        muted = Color(0xFF8A857B),
    )
    NovelTheme.SEPIA -> NovelPaperTheme(
        background = KotoriColors.paperSepia,
        ink = KotoriColors.paperSepiaInk,
        accent = KotoriColors.paperSepiaAccent,
        muted = Color(0xFF77705F),
    )
    NovelTheme.DARK -> NovelPaperTheme(
        background = Color(0xFF1A1723),
        ink = Color(0xFFD9D3E8),
        accent = Color(0xFF5EEAD4),
        muted = Color(0xFF8D84AC),
    )
    NovelTheme.BLACK -> NovelPaperTheme(
        background = Color(0xFF000000),
        ink = Color(0xFFC9C4D6),
        accent = Color(0xFF5EEAD4),
        muted = Color(0xFF6E6590),
    )
}

private fun NovelFont.family(): FontFamily = when (this) {
    NovelFont.LITERATA -> LiterataFamily
    NovelFont.NOTO_SERIF -> FontFamily.Serif
    NovelFont.BE_VIETNAM -> BeVietnamProFamily
}

/**
 * Novel reader (design screen 10): paper background, Literata body with teal
 * drop cap, chapter label, progress row, tap-to-toggle chrome and an
 * always-dark glass settings sheet.
 */
@Composable
fun NovelReaderScreen(
    title: String,
    chapterLabel: String,
    content: String,
    startPercent: Int,
    onProgressChanged: (Int) -> Unit,
    preferences: NovelReaderPreferences,
    onNavigateUp: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var hasRestored by rememberSaveable(content) { mutableStateOf(false) }

    // Restore once the text is laid out, otherwise maxValue is still 0 and the jump is a no-op.
    LaunchedEffect(content, scrollState.maxValue) {
        if (content.isNotEmpty() && scrollState.maxValue > 0 && !hasRestored) {
            hasRestored = true
            if (startPercent > 0) {
                scrollState.scrollTo((scrollState.maxValue * startPercent / 100f).toInt())
            }
        }
    }

    val progressPercent by remember {
        derivedStateOf {
            if (scrollState.maxValue <= 0) 0 else (scrollState.value * 100 / scrollState.maxValue).coerceIn(0, 100)
        }
    }
    LaunchedEffect(progressPercent) { onProgressChanged(progressPercent) }
    val fontSize by preferences.fontSize.changes().collectAsState(initial = preferences.fontSize.get())
    val font by preferences.fontFamily.changes().collectAsState(initial = preferences.fontFamily.get())
    val theme by preferences.theme.changes().collectAsState(initial = preferences.theme.get())
    val spacing by preferences.lineSpacing.changes().collectAsState(initial = preferences.lineSpacing.get())

    val paper = theme.paper()
    var chromeVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paper.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 22.dp),
        ) {
            Box(modifier = Modifier.height(52.dp))
            Text(
                text = chapterLabel.uppercase(),
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 0.14.em,
                color = paper.accent,
            )
            NovelBody(
                content = content,
                fontFamily = font.family(),
                fontSize = fontSize,
                lineHeightMultiplier = spacing.multiplier,
                ink = paper.ink,
                accent = paper.accent,
            )
            Box(modifier = Modifier.height(90.dp))
        }

        // Top chrome
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(paper.background.copy(alpha = 0.95f), paper.background.copy(alpha = 0f)),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = paper.ink,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onNavigateUp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = paper.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = chapterLabel,
                        fontFamily = BeVietnamProFamily,
                        fontSize = 10.5.sp,
                        color = paper.accent,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = paper.ink,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { settingsVisible = true },
                )
            }
        }

        // Bottom progress chrome
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(paper.background.copy(alpha = 0f), paper.background.copy(alpha = 0.95f)),
                        ),
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "$progressPercent%",
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp,
                    color = paper.accent,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(paper.ink.copy(alpha = 0.15f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPercent / 100f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF14B8A6), Color(0xFF5EEAD4)),
                                ),
                            ),
                    )
                }

            }
        }

        // Settings sheet — always dark glass regardless of paper theme
        AnimatedVisibility(
            visible = settingsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            NovelReaderSettingsSheet(
                preferences = preferences,
                fontSize = fontSize,
                font = font,
                theme = theme,
                spacing = spacing,
                onDismiss = { settingsVisible = false },
            )
        }
    }
}

@Composable
private fun NovelBody(
    content: String,
    fontFamily: FontFamily,
    fontSize: Int,
    lineHeightMultiplier: Float,
    ink: Color,
    accent: Color,
) {
    val paragraphs = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    paragraphs.forEachIndexed { index, paragraph ->
        if (index == 0 && paragraph.isNotEmpty()) {
            // Teal drop cap on the opening paragraph
            Row(modifier = Modifier.padding(top = 14.dp)) {
                Text(
                    text = paragraph.first().uppercase(),
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    color = accent,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = paragraph.drop(1),
                    fontFamily = fontFamily,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * lineHeightMultiplier).sp,
                    color = ink,
                )
            }
        } else {
            Text(
                text = paragraph,
                fontFamily = fontFamily,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeightMultiplier).sp,
                color = ink,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun NovelReaderSettingsSheet(
    preferences: NovelReaderPreferences,
    fontSize: Int,
    font: NovelFont,
    theme: NovelTheme,
    spacing: NovelLineSpacing,
    onDismiss: () -> Unit,
) {
    val tealGradient = Brush.linearGradient(listOf(Color(0xFF14B8A6), Color(0xFF5EEAD4)))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KotoriShapes.sheet)
            .background(KotoriColors.bgSheet)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* consume */ }
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Grab handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 38.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x33FFFFFF))
                .clickable(onClick = onDismiss),
        )

        // Font size: A− … A+
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("A−", fontFamily = UnboundedFamily, fontSize = 12.sp, color = KotoriColors.textSecondary)
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { preferences.fontSize.set(it.toInt().coerceIn(12, 28)) },
                valueRange = 12f..28f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF5EEAD4),
                    activeTrackColor = Color(0xFF14B8A6),
                    inactiveTrackColor = Color(0x1FFFFFFF),
                ),
            )
            Text("A+", fontFamily = UnboundedFamily, fontSize = 14.sp, color = KotoriColors.textPrimary)
            Text(
                text = "${fontSize}px",
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = KotoriColors.textMuted,
            )
        }

        // Font family segments
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NovelFont.entries.forEach { candidate ->
                val selected = candidate == font
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (selected) {
                                Modifier
                                    .clip(KotoriShapes.chip)
                                    .background(tealGradient)
                            } else {
                                Modifier
                                    .clip(KotoriShapes.chip)
                                    .background(Color(0x0FFFFFFF))
                            },
                        )
                        .clickable { preferences.fontFamily.set(candidate) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = candidate.label,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        color = if (selected) Color(0xFF0B1512) else KotoriColors.textSecondary,
                    )
                }
            }
        }

        // Theme swatches + line spacing
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NovelTheme.entries.forEach { candidate ->
                val paper = candidate.paper()
                val selected = candidate == theme
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(paper.background)
                        .then(
                            if (selected) {
                                Modifier.background(Color.Transparent).padding(0.dp)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { preferences.theme.set(candidate) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF14B8A6)),
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f))
            NovelLineSpacing.entries.forEach { candidate ->
                val selected = candidate == spacing
                Icon(
                    imageVector = Icons.Filled.FormatLineSpacing,
                    contentDescription = candidate.label,
                    tint = if (selected) Color(0xFF5EEAD4) else KotoriColors.textFaint,
                    modifier = Modifier
                        .size(if (selected) 24.dp else 20.dp)
                        .clickable { preferences.lineSpacing.set(candidate) },
                )
            }
        }
    }
}
