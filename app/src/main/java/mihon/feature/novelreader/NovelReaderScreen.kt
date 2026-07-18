package mihon.feature.novelreader

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.LiterataFamily
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.tachiyomi.source.novel.builtin.DocLnImagePolicy
import kotlinx.coroutines.launch
import mihon.feature.novelreader.NovelReaderPreferences.NovelFont
import mihon.feature.novelreader.NovelReaderPreferences.NovelLineSpacing
import mihon.feature.novelreader.NovelReaderPreferences.NovelReadingMode
import mihon.feature.novelreader.NovelReaderPreferences.NovelTheme
import java.util.Locale
import kotlin.math.abs

/**
 * Marks a line of a chapter's text as an inline illustration rather than prose: the sentinel is
 * immediately followed by the image's absolute URL.
 *
 * `NovelSource.getChapterText` returns one String, so a source with pictures encodes them into that
 * String; this private-use code point cannot collide with real prose. Sources declare the same code
 * point themselves rather than importing it from this feature package - see `DocLnSource`.
 */
private const val NOVEL_IMAGE_SENTINEL: String = "\uE000"

data class NovelPaperTheme(
    val background: Color,
    val ink: Color,
    val accent: Color,
    val muted: Color,
)

/** One laid-out unit of a chapter: either a paragraph of prose or an illustration. */
private sealed interface NovelBlock {
    data class Prose(val text: String) : NovelBlock
    data class Illustration(val url: String) : NovelBlock
}

/**
 * Splits chapter text into blocks.
 *
 * Chapter text is author-controlled, so this is the last gate before an illustration URL becomes a
 * network request: a sentinel line is kept only if it carries a trusted HTTPS image URL. Blank,
 * malformed and untrusted lines are dropped rather than shown, which also stops the sentinel itself
 * from leaking into the prose.
 */
private fun String.toNovelBlocks(): List<NovelBlock> = split("\n").mapNotNull { line ->
    val trimmed = line.trim()
    when {
        trimmed.isEmpty() -> null
        trimmed.startsWith(NOVEL_IMAGE_SENTINEL) ->
            trimmed.removePrefix(NOVEL_IMAGE_SENTINEL).trim()
                .takeIf(DocLnImagePolicy::isTrusted)
                ?.let(NovelBlock::Illustration)
        else -> NovelBlock.Prose(trimmed)
    }
}

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
    val ttsController = remember { NovelTtsController(context.applicationContext) }

    DisposableEffect(ttsController) {
        onDispose(ttsController::shutdown)
    }
    LaunchedEffect(content) {
        ttsController.stop()
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
                content = content,
                fontFamily = font.family(),
                fontSize = fontSize,
                lineHeightMultiplier = spacing.multiplier,
                ink = paper.ink,
                accent = paper.accent,
                muted = paper.muted,
            )
            NovelChapterEnd(
                paper = paper,
                bookmarked = bookmarked,
                nextChapterLabel = nextChapterLabel,
                onToggleBookmark = onToggleBookmark,
                onListen = {
                    ttsControlsVisible = true
                    ttsController.toggle(content)
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
                        ttsController.toggle(content)
                    },
                ) {
                    Icon(
                        imageVector = if (ttsController.status == NovelTtsStatus.PLAYING) {
                            Icons.Filled.Pause
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (ttsController.status == NovelTtsStatus.PLAYING) {
                            "Tạm dừng nghe"
                        } else {
                            "Nghe chương"
                        },
                        tint = if (ttsController.status == NovelTtsStatus.PLAYING) paper.accent else paper.ink,
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
            NovelTtsControls(
                controller = ttsController,
                content = content,
                paper = paper,
                onDismiss = { ttsControlsVisible = false },
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
                fontSize = fontSize,
                font = font,
                theme = theme,
                spacing = spacing,
                readingMode = readingMode,
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
private fun NovelTtsControls(
    controller: NovelTtsController,
    content: String,
    paper: NovelPaperTheme,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(paper.background.copy(alpha = 0.97f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            enabled = controller.status != NovelTtsStatus.ERROR,
            onClick = { controller.toggle(content) },
        ) {
            Icon(
                imageVector = if (controller.status == NovelTtsStatus.PLAYING) {
                    Icons.Filled.Pause
                } else {
                    Icons.Filled.PlayArrow
                },
                contentDescription = if (controller.status == NovelTtsStatus.PLAYING) {
                    "Tạm dừng"
                } else {
                    "Tiếp tục nghe"
                },
                tint = paper.accent,
            )
        }
        if (controller.status == NovelTtsStatus.ERROR) {
            Text(
                text = "Thiết bị chưa cài giọng đọc",
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = paper.muted,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = "${controller.rate}×",
                fontFamily = BeVietnamProFamily,
                fontSize = 10.sp,
                color = paper.muted,
            )
            Slider(
                value = controller.rate,
                onValueChange = controller::updateRate,
                valueRange = 0.7f..1.5f,
                steps = 7,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = paper.accent,
                    activeTrackColor = paper.accent,
                    inactiveTrackColor = paper.ink.copy(alpha = 0.14f),
                ),
            )
        }
        IconButton(onClick = controller::stop) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Dừng nghe",
                tint = paper.ink,
            )
        }
        Text(
            text = "Ẩn",
            fontFamily = BeVietnamProFamily,
            fontSize = 11.sp,
            color = paper.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private enum class NovelTtsStatus {
    INITIALIZING,
    READY,
    PLAYING,
    PAUSED,
    ERROR,
}

private class NovelTtsController(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var session = 0
    private var currentChunk = 0
    private var chunks: List<String> = emptyList()
    private var pendingText: String? = null

    var status by mutableStateOf(NovelTtsStatus.INITIALIZING)
        private set
    var rate by mutableStateOf(1f)
        private set

    init {
        engine = TextToSpeech(context) { result ->
            mainHandler.post {
                if (result != TextToSpeech.SUCCESS) {
                    status = NovelTtsStatus.ERROR
                    return@post
                }
                initialized = true
                engine?.apply {
                    val vietnamese = Locale.forLanguageTag("vi-VN")
                    if (isLanguageAvailable(vietnamese) >= TextToSpeech.LANG_AVAILABLE) {
                        language = vietnamese
                    } else {
                        language = Locale.getDefault()
                    }
                    setSpeechRate(rate)
                    setOnUtteranceProgressListener(ttsListener)
                }
                status = NovelTtsStatus.READY
                pendingText?.also {
                    pendingText = null
                    start(it)
                }
            }
        }
    }

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (utteranceSession, index) = utteranceId.toSessionAndIndex() ?: return
            mainHandler.post {
                if (utteranceSession == session) {
                    currentChunk = index
                    status = NovelTtsStatus.PLAYING
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            val (utteranceSession, index) = utteranceId.toSessionAndIndex() ?: return
            mainHandler.post {
                if (utteranceSession == session && index == chunks.lastIndex) {
                    currentChunk = 0
                    status = NovelTtsStatus.READY
                }
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onError(utteranceId: String?) {
            reportError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            reportError(utteranceId)
        }

        private fun reportError(utteranceId: String?) {
            val utteranceSession = utteranceId.toSessionAndIndex()?.first ?: return
            mainHandler.post {
                if (utteranceSession == session) status = NovelTtsStatus.ERROR
            }
        }
    }

    fun toggle(content: String) {
        when (status) {
            NovelTtsStatus.INITIALIZING -> pendingText = content
            NovelTtsStatus.PLAYING -> pause()
            NovelTtsStatus.PAUSED -> enqueueFrom(currentChunk)
            NovelTtsStatus.READY -> start(content)
            NovelTtsStatus.ERROR -> if (initialized) start(content)
        }
    }

    fun updateRate(value: Float) {
        rate = value.coerceIn(0.7f, 1.5f)
        engine?.setSpeechRate(rate)
        if (status == NovelTtsStatus.PLAYING) {
            engine?.stop()
            enqueueFrom(currentChunk)
        }
    }

    fun stop() {
        pendingText = null
        session++
        engine?.stop()
        currentChunk = 0
        chunks = emptyList()
        status = if (initialized) NovelTtsStatus.READY else NovelTtsStatus.INITIALIZING
    }

    fun shutdown() {
        pendingText = null
        session++
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun start(content: String) {
        if (!initialized) {
            pendingText = content
            status = NovelTtsStatus.INITIALIZING
            return
        }
        chunks = content.toSpeechChunks()
        currentChunk = 0
        if (chunks.isEmpty()) {
            status = NovelTtsStatus.ERROR
            return
        }
        enqueueFrom(0)
    }

    private fun pause() {
        session++
        engine?.stop()
        status = NovelTtsStatus.PAUSED
    }

    private fun enqueueFrom(index: Int) {
        val tts = engine ?: return
        if (chunks.isEmpty()) return
        session++
        val activeSession = session
        status = NovelTtsStatus.PLAYING
        chunks.drop(index).forEachIndexed { offset, chunk ->
            val chunkIndex = index + offset
            tts.speak(
                chunk,
                if (offset == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                Bundle(),
                "novel:$activeSession:$chunkIndex",
            )
        }
    }

    private fun String?.toSessionAndIndex(): Pair<Int, Int>? {
        val parts = this?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != "novel") return null
        return (parts[1].toIntOrNull() ?: return null) to (parts[2].toIntOrNull() ?: return null)
    }
}

private fun String.toSpeechChunks(): List<String> {
    val maxLength = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
    val prose = toNovelBlocks()
        .filterIsInstance<NovelBlock.Prose>()
        .joinToString("\n") { it.text }
        .trim()
    if (prose.isEmpty()) return emptyList()

    val result = mutableListOf<String>()
    var remaining = prose
    while (remaining.isNotEmpty()) {
        if (remaining.length <= maxLength) {
            result += remaining
            break
        }
        val window = remaining.take(maxLength)
        val sentenceBreak = window.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        val wordBreak = window.lastIndexOf(' ')
        val cut = maxOf(sentenceBreak + 1, wordBreak).takeIf { it >= maxLength / 2 } ?: maxLength
        result += remaining.take(cut).trim()
        remaining = remaining.drop(cut).trimStart()
    }
    return result.filter(String::isNotEmpty)
}

@Composable
private fun NovelBody(
    content: String,
    fontFamily: FontFamily,
    fontSize: Int,
    lineHeightMultiplier: Float,
    ink: Color,
    accent: Color,
    muted: Color,
) {
    val blocks = remember(content) { content.toNovelBlocks() }
    // A chapter can open on an illustration, so the drop cap follows the first prose block rather
    // than the first block; an illustration-only chapter simply never draws one.
    val dropCapIndex = remember(blocks) { blocks.indexOfFirst { it is NovelBlock.Prose } }
    blocks.forEachIndexed { index, block ->
        when (block) {
            is NovelBlock.Illustration -> NovelIllustration(url = block.url, muted = muted)
            is NovelBlock.Prose -> if (index == dropCapIndex) {
                // Teal drop cap on the opening paragraph
                Row(modifier = Modifier.padding(top = 14.dp)) {
                    Text(
                        text = block.text.first().uppercase(),
                        fontFamily = UnboundedFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp,
                        color = accent,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = block.text.drop(1),
                        fontFamily = fontFamily,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineHeightMultiplier).sp,
                        color = ink,
                    )
                }
            } else {
                Text(
                    text = block.text,
                    fontFamily = fontFamily,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * lineHeightMultiplier).sp,
                    color = ink,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * Inline illustration, sized to the column width with its aspect ratio kept. A load failure is
 * non-fatal: the chapter keeps rendering and only this block degrades to a compact placeholder.
 */
@Composable
private fun NovelIllustration(url: String, muted: Color) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(context, url) { novelImageRequest(context, url) }
    if (failed) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(muted.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Ảnh không tải được",
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = muted,
            )
        }
    } else {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    }
}

private val DOC_LN_REFERER_HEADERS = NetworkHeaders.Builder()
    .set("Referer", DocLnImagePolicy.REFERER)
    .build()

internal fun novelImageRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply {
            if (DocLnImagePolicy.requiresReferer(url)) {
                httpHeaders(DOC_LN_REFERER_HEADERS)
            }
        }
        .build()

@Composable
private fun NovelReaderSettingsSheet(
    preferences: NovelReaderPreferences,
    fontSize: Int,
    font: NovelFont,
    theme: NovelTheme,
    spacing: NovelLineSpacing,
    readingMode: NovelReadingMode,
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
