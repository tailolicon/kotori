package mihon.feature.novelreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.LiterataFamily
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import kotlinx.coroutines.launch
import mihon.feature.novelreader.NovelReaderPreferences.NovelFont
import mihon.feature.novelreader.NovelReaderPreferences.NovelLineSpacing
import mihon.feature.novelreader.NovelReaderPreferences.NovelReadingMode
import mihon.feature.novelreader.NovelReaderPreferences.NovelTheme
import mihon.feature.novelreader.tts.NovelTtsController
import mihon.feature.novelreader.tts.NovelTtsPreferences
import mihon.feature.novelreader.tts.NovelTtsStatus
import mihon.feature.novelreader.tts.buildSpeechScript
import kotlin.math.abs

/**
 * Where the sentence being read aloud is parked on screen, as a fraction of the viewport height.
 * Kept above centre so the lines that come next stay visible, the way a lyrics view does.
 */
private const val FOLLOW_ANCHOR = 0.32f

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
    NovelTheme.PINK -> NovelPaperTheme(
        background = Color(0xFFFFEEEE),
        ink = Color(0xFF382728),
        accent = Color(0xFFBE5F72),
        muted = Color(0xFF8A7074),
    )
    NovelTheme.CREAM -> NovelPaperTheme(
        background = Color(0xFFFFF8E8),
        ink = Color(0xFF342E22),
        accent = Color(0xFF9B6A25),
        muted = Color(0xFF817762),
    )
    NovelTheme.SEPIA -> NovelPaperTheme(
        background = KotoriColors.paperSepia,
        ink = KotoriColors.paperSepiaInk,
        accent = KotoriColors.paperSepiaAccent,
        muted = Color(0xFF77705F),
    )
    NovelTheme.KHAKI -> NovelPaperTheme(
        background = Color(0xFFD8CCAE),
        ink = Color(0xFF302B20),
        accent = Color(0xFF75602B),
        muted = Color(0xFF716955),
    )
    NovelTheme.ROSE -> NovelPaperTheme(
        background = Color(0xFFC8B2AD),
        ink = Color(0xFF302425),
        accent = Color(0xFF7F3F4C),
        muted = Color(0xFF725D5D),
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
    ttsPreferences: NovelTtsPreferences,
    onNavigateUp: () -> Unit,
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    previousChapterLabel: String? = null,
    nextChapterLabel: String? = null,
    chapterNavigationEnabled: Boolean = true,
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
) {
    // A fresh scroll state is essential when content changes; otherwise a chapter loaded from the
    // previous chapter's 100% position can immediately report another completion and be skipped.
    val scrollState = remember(content) { ScrollState(0) }
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
    val readingMode by preferences.readingMode.changes().collectAsState(initial = preferences.readingMode.get())

    val paper = theme.paper()
    var chromeVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableStateOf(0) }
    var horizontalDrag by remember { mutableStateOf(0f) }
    var boundaryOffset by remember(content) { mutableStateOf(0f) }
    var ttsControlsVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pullThreshold = with(density) { 72.dp.toPx() }
    val latestPreviousChapter by rememberUpdatedState(onPreviousChapter)
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val canOpenPrevious = previousChapterLabel != null && chapterNavigationEnabled
    val canOpenNext = nextChapterLabel != null && chapterNavigationEnabled
    val followPlayback by ttsPreferences.followPlayback.changes()
        .collectAsState(initial = ttsPreferences.followPlayback.get())

    // Blocks and the speech script are two views of the same chapter and must stay index-aligned,
    // so they are derived together from one parse. Illustrations contribute no sentences but keep
    // their slot, otherwise every highlight after a picture would land a paragraph early.
    val blocks = remember(content) { content.toNovelBlocks() }
    val script = remember(blocks) {
        buildSpeechScript(blocks.map { (it as? NovelBlock.Prose)?.text })
    }

    val ttsController = remember(ttsPreferences) {
        NovelTtsController(context.applicationContext, ttsPreferences)
    }
    val ttsState = ttsController.state
    val blockOffsets = remember(blocks) { mutableStateMapOf<Int, Int>() }

    DisposableEffect(ttsController) { onDispose(ttsController::release) }
    LaunchedEffect(script) { ttsController.setScript(script) }

    // Follow playback the way a lyrics view does: keep the line being read in the upper third, so
    // the listener can see what comes next rather than reading at the very bottom of the screen.
    LaunchedEffect(ttsState.sentence, followPlayback) {
        if (!followPlayback || readingMode != NovelReadingMode.SCROLL) return@LaunchedEffect
        val sentence = script[ttsState.sentence] ?: return@LaunchedEffect
        val top = blockOffsets[sentence.blockIndex] ?: return@LaunchedEffect
        val target = (top - viewportHeight * FOLLOW_ANCHOR).toInt()
        scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    val boundaryConnection = remember(
        scrollState,
        readingMode,
        canOpenPrevious,
        canOpenNext,
        pullThreshold,
    ) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (readingMode != NovelReadingMode.SCROLL || source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                val atTop = scrollState.value == 0
                val atBottom = scrollState.value == scrollState.maxValue
                val pullingPrevious = atTop && available.y > 0f && canOpenPrevious
                val pullingNext = atBottom && available.y < 0f && canOpenNext
                if (!pullingPrevious && !pullingNext) return Offset.Zero

                val resisted = available.y * 0.32f
                boundaryOffset = (boundaryOffset + resisted)
                    .coerceIn(-pullThreshold * 1.45f, pullThreshold * 1.45f)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val pulled = boundaryOffset
                if (abs(pulled) >= pullThreshold) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    boundaryOffset = 0f
                    if (pulled < 0f && canOpenNext) {
                        latestNextChapter()
                    } else if (pulled > 0f && canOpenPrevious) {
                        latestPreviousChapter()
                    }
                } else if (pulled != 0f) {
                    Animatable(pulled).animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) {
                        boundaryOffset = value
                    }
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeight = it.height }
            .background(paper.background)
            .nestedScroll(boundaryConnection)
            .then(
                if (readingMode == NovelReadingMode.PAGED && !settingsVisible) {
                    Modifier.pointerInput(content, viewportHeight, scrollState.maxValue) {
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDrag = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                horizontalDrag += amount
                            },
                            onDragCancel = { horizontalDrag = 0f },
                            onDragEnd = {
                                val distance = horizontalDrag
                                horizontalDrag = 0f
                                if (abs(distance) >= size.width * 0.12f && viewportHeight > 0) {
                                    val step = (viewportHeight * 0.82f).toInt().coerceAtLeast(1)
                                    when {
                                        distance < 0 && scrollState.value >= scrollState.maxValue &&
                                            canOpenNext -> latestNextChapter()
                                        distance > 0 && scrollState.value <= 0 &&
                                            canOpenPrevious -> latestPreviousChapter()
                                        else -> {
                                            val target = if (distance < 0) {
                                                scrollState.value + step
                                            } else {
                                                scrollState.value - step
                                            }
                                            scope.launch {
                                                scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // The settings sheet consumes its own taps, so any tap that reaches here is outside
                // it — close the sheet first, otherwise toggle the reader chrome.
                if (settingsVisible) settingsVisible = false else chromeVisible = !chromeVisible
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = boundaryOffset }
                .verticalScroll(scrollState, enabled = readingMode == NovelReadingMode.SCROLL)
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
                blocks = blocks,
                script = script,
                highlight = NovelHighlight(
                    sentence = script[ttsState.sentence],
                    seekEnabled = ttsState.isActive,
                ),
                fontFamily = font.family(),
                fontSize = fontSize,
                lineHeightMultiplier = spacing.multiplier,
                ink = paper.ink,
                accent = paper.accent,
                muted = paper.muted,
                onSeek = { index ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    ttsControlsVisible = true
                    ttsController.seekTo(index)
                    if (!ttsState.isActive) ttsController.play(index)
                },
                onTapOutsideSeek = {
                    if (settingsVisible) settingsVisible = false else chromeVisible = !chromeVisible
                },
                onBlockPositioned = { index, top -> blockOffsets[index] = top },
            )
            NovelChapterEnd(
                paper = paper,
                bookmarked = bookmarked,
                nextChapterLabel = nextChapterLabel,
                onToggleBookmark = onToggleBookmark,
                onListen = {
                    ttsControlsVisible = true
                    ttsController.toggle()
                },
            )
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
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = paper.ink,
                    )
                }
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
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (bookmarked) "Bỏ đánh dấu chương" else "Đánh dấu chương",
                        tint = if (bookmarked) paper.accent else paper.ink,
                    )
                }
                IconButton(
                    onClick = {
                        ttsControlsVisible = true
                        ttsController.toggle()
                        ttsController.refreshVoices()
                    },
                ) {
                    val playing = ttsState.status == NovelTtsStatus.PLAYING
                    Icon(
                        imageVector = if (playing) {
                            Icons.Filled.Pause
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (playing) "Tạm dừng nghe" else "Nghe chương",
                        tint = if (playing) paper.accent else paper.ink,
                    )
                }
                IconButton(onClick = { settingsVisible = !settingsVisible }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Cài đặt đọc",
                        tint = paper.ink,
                    )
                }
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

        AnimatedVisibility(
            visible = ttsControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 54.dp),
        ) {
            NovelTtsPlayer(
                state = ttsState,
                script = script,
                controller = ttsController,
                paper = paper,
                onDismiss = {
                    ttsControlsVisible = false
                    ttsController.stop()
                },
            )
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
                ttsPreferences = ttsPreferences,
                fontSize = fontSize,
                font = font,
                theme = theme,
                spacing = spacing,
                readingMode = readingMode,
                followPlayback = followPlayback,
                onDismiss = { settingsVisible = false },
            )
        }
    }
}

@Composable
private fun NovelChapterEnd(
    paper: NovelPaperTheme,
    bookmarked: Boolean,
    nextChapterLabel: String?,
    onToggleBookmark: () -> Unit,
    onListen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 330.dp)
            .padding(top = 84.dp, bottom = 132.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "— HẾT CHƯƠNG —",
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.12.em,
            color = paper.muted,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.clickable(onClick = onToggleBookmark),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = if (bookmarked) paper.accent else paper.ink,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = if (bookmarked) "Đã đánh dấu" else "Đánh dấu",
                    fontFamily = BeVietnamProFamily,
                    fontSize = 12.sp,
                    color = paper.ink,
                )
            }
            Column(
                modifier = Modifier.clickable(onClick = onListen),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = paper.ink,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = "Nghe",
                    fontFamily = BeVietnamProFamily,
                    fontSize = 12.sp,
                    color = paper.ink,
                )
            }
        }
        Text(
            text = nextChapterLabel?.let { "Kéo tiếp để đọc\n$it" } ?: "Đây là chương mới nhất",
            fontFamily = BeVietnamProFamily,
            fontSize = 11.sp,
            color = paper.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NovelReaderSettingsSheet(
    preferences: NovelReaderPreferences,
    ttsPreferences: NovelTtsPreferences,
    fontSize: Int,
    font: NovelFont,
    theme: NovelTheme,
    spacing: NovelLineSpacing,
    readingMode: NovelReadingMode,
    followPlayback: Boolean,
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
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
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

        SettingsLabel("Chế độ đọc")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(KotoriShapes.chip)
                .background(Color(0x0FFFFFFF)),
        ) {
            NovelReadingMode.entries.forEach { candidate ->
                val selected = candidate == readingMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (selected) {
                                Modifier.background(tealGradient)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { preferences.readingMode.set(candidate) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = candidate.label,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = if (selected) Color(0xFF0B1512) else KotoriColors.textSecondary,
                    )
                }
            }
        }

        SettingsLabel("Cỡ chữ")
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

        SettingsLabel("Màu")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NovelTheme.entries.forEach { candidate ->
                val paper = candidate.paper()
                val selected = candidate == theme
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(paper.background)
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
        }

        SettingsLabel("Font chữ")
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

        SettingsLabel("Giãn dòng")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovelLineSpacing.entries.forEach { candidate ->
                val selected = candidate == spacing
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(KotoriShapes.chip)
                        .then(
                            if (selected) {
                                Modifier.background(Color(0x2214B8A6))
                            } else {
                                Modifier.background(Color(0x0FFFFFFF))
                            },
                        )
                        .clickable { preferences.lineSpacing.set(candidate) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FormatLineSpacing,
                            contentDescription = null,
                            tint = if (selected) Color(0xFF5EEAD4) else KotoriColors.textFaint,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = candidate.label,
                            fontFamily = BeVietnamProFamily,
                            fontSize = 10.5.sp,
                            color = if (selected) Color(0xFF5EEAD4) else KotoriColors.textSecondary,
                        )
                    }
                }
            }
        }

        SettingsLabel("Khi nghe")
        SettingsToggle(
            label = "Tự cuộn theo giọng đọc",
            checked = followPlayback,
            onToggle = { ttsPreferences.followPlayback.set(it) },
        )
    }
}

/** A labelled on/off row, styled to match the sheet's chips rather than Material's switch. */
@Composable
private fun SettingsToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KotoriShapes.chip)
            .background(if (checked) Color(0x2214B8A6) else Color(0x0FFFFFFF))
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontFamily = BeVietnamProFamily,
            fontSize = 11.sp,
            color = if (checked) Color(0xFF5EEAD4) else KotoriColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (checked) "Bật" else "Tắt",
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            color = if (checked) Color(0xFF5EEAD4) else KotoriColors.textFaint,
        )
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        fontFamily = BeVietnamProFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        color = KotoriColors.textPrimary,
    )
}
