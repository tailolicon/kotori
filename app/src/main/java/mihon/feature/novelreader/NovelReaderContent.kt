package mihon.feature.novelreader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.presentation.core.screens.LoadingScreen

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
    onNavigateUp: () -> Unit,
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
        // restyled to match the native reader.
        state.webUrl != null -> NovelWebChapterView(
            url = state.webUrl!!,
            preferences = preferences,
            startPercent = state.startPercent,
            onProgressChanged = { percent ->
                onProgressChanged(percent)
                if (percent >= 100 && nextChapter != null && !state.isChangingChapter) {
                    transitionDirection = ChapterTransitionDirection.NEXT
                    viewModel.loadChapter(nextChapter.id)
                }
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
                    onNavigateUp = onNavigateUp,
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
