package mihon.feature.novelreader

import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.appbars.ReaderToolTile
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import mihon.feature.novelreader.tts.NovelTtsPreferences
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class NovelReaderPage(
    val chapterId: Long,
    val title: String,
    val chapterLabel: String,
    val content: String,
    val startPercent: Int,
)

private enum class ChapterTransitionDirection {
    PREVIOUS,
    NEXT,
}

/**
 * Binds [NovelReaderViewModel] to [NovelReaderScreen], which is otherwise a dumb renderer.
 */
@Composable
fun NovelReaderContent(
    viewModel: NovelReaderViewModel,
    preferences: NovelReaderPreferences,
    ttsPreferences: NovelTtsPreferences,
    onNavigateUp: () -> Unit,
    menuVisible: Boolean,
    onSetMenuVisible: (Boolean) -> Unit,
    onOpenEntry: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var completedChapterId by remember { mutableStateOf<Long?>(null) }
    var transitionDirection by remember { mutableStateOf(ChapterTransitionDirection.NEXT) }
    val adjacentChapters = remember(state.manga, state.chapter?.id, state.chapters) {
        val manga = state.manga
        val chapter = state.chapter
        if (manga == null || chapter == null) {
            null to null
        } else {
            val ordered = state.chapters.sortedWith(getChapterSort(manga, sortDescending = false))
            val currentIndex = ordered.indexOfFirst { it.id == chapter.id }
            if (currentIndex < 0) {
                null to null
            } else {
                ordered.getOrNull(currentIndex - 1) to ordered.getOrNull(currentIndex + 1)
            }
        }
    }
    val previousChapter = adjacentChapters.first
    val nextChapter = adjacentChapters.second

    LaunchedEffect(state.chapter?.id) {
        completedChapterId = null
    }

    val onProgressChanged: (Int) -> Unit = { percent ->
        viewModel.onProgress(percent)
        val chapterId = state.chapter?.id
        if (percent >= 100 && chapterId != null && completedChapterId != chapterId) {
            completedChapterId = chapterId
            viewModel.flushProgress()
        }
    }

    when {
        state.isLoading -> LoadingScreen(Modifier.fillMaxSize())
        state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(text = state.error.orEmpty())
        }
        // Sources that hand their text to their own page scripts render the chapter themselves,
        // restyled to match the native reader. The chrome on top is still the shared reader chrome.
        state.webUrl != null -> NovelWebReaderContent(
            viewModel = viewModel,
            url = state.webUrl!!,
            preferences = preferences,
            ttsPreferences = ttsPreferences,
            onNavigateUp = onNavigateUp,
            menuVisible = menuVisible,
            onSetMenuVisible = onSetMenuVisible,
            onOpenEntry = onOpenEntry,
            previousChapterId = previousChapter?.id,
            nextChapterId = nextChapter?.id,
            onProgressChanged = onProgressChanged,
            onChangeChapter = { chapterId, direction ->
                transitionDirection = direction
                viewModel.loadChapter(chapterId)
            },
        )
        state.chapter != null -> {
            val page = NovelReaderPage(
                chapterId = state.chapter!!.id,
                title = state.manga?.title.orEmpty(),
                chapterLabel = state.chapter!!.name,
                content = state.content,
                startPercent = state.startPercent,
            )
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val movingForward = transitionDirection == ChapterTransitionDirection.NEXT
                    val enter = slideInVertically(
                        animationSpec = tween(durationMillis = 380),
                    ) { height -> if (movingForward) height else -height } + fadeIn(tween(220))
                    val exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 380),
                    ) { height -> if (movingForward) -height else height } + fadeOut(tween(220))
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "Novel chapter transition",
            ) { visiblePage ->
                NovelReaderScreen(
                    title = visiblePage.title,
                    chapterLabel = visiblePage.chapterLabel,
                    content = visiblePage.content,
                    startPercent = visiblePage.startPercent,
                    onProgressChanged = { percent ->
                        if (state.chapter?.id == visiblePage.chapterId) {
                            onProgressChanged(percent)
                        }
                    },
                    preferences = preferences,
                    ttsPreferences = ttsPreferences,
                    onNavigateUp = onNavigateUp,
                    menuVisible = menuVisible,
                    onSetMenuVisible = onSetMenuVisible,
                    onOpenEntry = onOpenEntry,
                    bookmarked = state.chapter?.takeIf { it.id == visiblePage.chapterId }?.bookmark == true,
                    onToggleBookmark = {
                        if (state.chapter?.id == visiblePage.chapterId) {
                            viewModel.toggleBookmark()
                        }
                    },
                    previousChapterLabel = previousChapter?.name,
                    nextChapterLabel = nextChapter?.name,
                    chapterNavigationEnabled = !state.isChangingChapter,
                    onPreviousChapter = {
                        previousChapter?.let {
                            transitionDirection = ChapterTransitionDirection.PREVIOUS
                            viewModel.loadChapter(it.id)
                        }
                    },
                    onNextChapter = {
                        nextChapter?.let {
                            transitionDirection = ChapterTransitionDirection.NEXT
                            viewModel.loadChapter(it.id)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The site-rendered chapter path, wearing the same reader chrome as everything else: the shared
 * app bars over the WebView, driven by taps and scroll deltas reported from the page's scripts.
 */
@Composable
private fun NovelWebReaderContent(
    viewModel: NovelReaderViewModel,
    url: String,
    preferences: NovelReaderPreferences,
    ttsPreferences: NovelTtsPreferences,
    onNavigateUp: () -> Unit,
    menuVisible: Boolean,
    onSetMenuVisible: (Boolean) -> Unit,
    onOpenEntry: () -> Unit,
    previousChapterId: Long?,
    nextChapterId: Long?,
    onProgressChanged: (Int) -> Unit,
    onChangeChapter: (Long, ChapterTransitionDirection) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current

    val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
    val menuHideThreshold = remember { readerPreferences.readerHideThreshold.get().threshold }
    val showPageNumber by readerPreferences.showPageNumber.changes()
        .collectAsState(initial = readerPreferences.showPageNumber.get())

    var webPercent by remember(state.chapter?.id) { mutableStateOf(state.startPercent) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var settingsVisible by remember { mutableStateOf(false) }

    val fontSize by preferences.fontSize.changes().collectAsState(initial = preferences.fontSize.get())
    val font by preferences.fontFamily.changes().collectAsState(initial = preferences.fontFamily.get())
    val theme by preferences.theme.changes().collectAsState(initial = preferences.theme.get())
    val spacing by preferences.lineSpacing.changes().collectAsState(initial = preferences.lineSpacing.get())
    val readingMode by preferences.readingMode.changes().collectAsState(initial = preferences.readingMode.get())
    val followPlayback by ttsPreferences.followPlayback.changes()
        .collectAsState(initial = ttsPreferences.followPlayback.get())

    val chapterNavigationEnabled = !state.isChangingChapter
    val isFinalChapter = nextChapterId == null

    Box(Modifier.fillMaxSize()) {
        NovelWebChapterView(
            url = url,
            preferences = preferences,
            startPercent = state.startPercent,
            onProgressChanged = { percent ->
                webPercent = percent
                onProgressChanged(percent)
                if (percent >= 100 && nextChapterId != null && chapterNavigationEnabled) {
                    onChangeChapter(nextChapterId, ChapterTransitionDirection.NEXT)
                }
            },
            onTap = {
                if (settingsVisible) settingsVisible = false else onSetMenuVisible(!menuVisible)
            },
            onScrollDelta = { dyCss ->
                // The page reports CSS pixels; the manga threshold is in device pixels.
                val dy = dyCss * density.density
                if ((dy > menuHideThreshold || dy < -menuHideThreshold) && menuVisible) {
                    onSetMenuVisible(false)
                }
                if (webPercent >= 100 && isFinalChapter && !menuVisible) {
                    onSetMenuVisible(true)
                }
            },
            onWebViewReady = { webView = it },
        )

        if (!menuVisible && showPageNumber) {
            ReaderPageIndicator(
                currentPage = webPercent,
                totalPages = 100,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        ReaderAppBars(
            visible = menuVisible,
            mangaTitle = state.manga?.title,
            chapterTitle = state.chapter?.name,
            navigateUp = onNavigateUp,
            onClickTopAppBar = onOpenEntry,
            bookmarked = state.chapter?.bookmark == true,
            onToggleBookmarked = viewModel::toggleBookmark,
            onOpenInWebView = null,
            onOpenInBrowser = { context.openInBrowser(url.toUri(), forceDefaultBrowser = false) },
            onShare = { context.startActivity(url.toUri().toShareIntent(context, type = "text/plain")) },
            chapterNavigatorType = ChapterNavigatorType.HORIZONTAL_LTR,
            verticalNavigatorHeight = 1f,
            onNextChapter = {
                nextChapterId?.let { onChangeChapter(it, ChapterTransitionDirection.NEXT) }
            },
            enabledNext = nextChapterId != null && chapterNavigationEnabled,
            onPreviousChapter = {
                previousChapterId?.let { onChangeChapter(it, ChapterTransitionDirection.PREVIOUS) }
            },
            enabledPrevious = previousChapterId != null && chapterNavigationEnabled,
            currentPage = webPercent.coerceAtLeast(1),
            totalPages = 100,
            onPageIndexChange = { index ->
                webView?.evaluateJavascript("kotoriScrollToPercent(${index + 1})", null)
            },
            onPageIndexChangeFinished = {},
            continuousSlider = true,
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .pointerInput(Unit) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderToolTile(
                        painter = rememberVectorPainter(Icons.Outlined.Settings),
                        label = stringResource(MR.strings.action_settings),
                        onClick = { settingsVisible = !settingsVisible },
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        )

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
