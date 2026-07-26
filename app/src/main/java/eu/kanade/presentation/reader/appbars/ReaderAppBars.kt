package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import tachiyomi.presentation.core.components.material.padding

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

/**
 * The reader chrome shared by the manga reader, the novel reader and the anime player: a top
 * app bar sliding in from the top and a chapter navigator plus tool row sliding in from the
 * bottom. What each medium shows inside the bars differs; the bars themselves — layout,
 * background, animation — must not, so this is the single implementation all three use.
 */
@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: (() -> Unit)?,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    chapterNavigatorType: ChapterNavigatorType,
    verticalNavigatorHeight: Float,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,

    bottomBar: @Composable () -> Unit,
    topBarExtraActions: List<AppBar.AppBarAction> = emptyList(),
    pageLabel: (Int) -> String = Int::toString,
    continuousSlider: Boolean = false,
    surface: ReaderBarSurface? = null,
) {
    val backgroundColor = surface?.background
        ?: MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    // Recolouring through the theme rather than per-widget: the bars are built from AppBar,
    // ChapterNavigator and Slider, each of which reads the scheme for a different role, so passing
    // colours down by hand would mean threading them through three components that the manga
    // reader shares.
    ReaderBarTheme(surface) {
    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { -it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { -it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            ReaderTopBar(
                modifier = Modifier
                    .background(backgroundColor)
                    .clickable(onClick = onClickTopAppBar),
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                navigateUp = navigateUp,
                bookmarked = bookmarked,
                onToggleBookmarked = onToggleBookmarked,
                onOpenInWebView = onOpenInWebView,
                onOpenInBrowser = onOpenInBrowser,
                onShare = onShare,
                extraActions = topBarExtraActions,
            )
        }

        if (!chapterNavigatorType.isHorizontal()) {
            val sliderOnLeft = chapterNavigatorType == ChapterNavigatorType.VERTICAL_LEFT
            CompositionLocalProvider(
                LocalLayoutDirection provides if (sliderOnLeft) LayoutDirection.Ltr else LayoutDirection.Rtl,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = slideInHorizontally(readerBarsSlideAnimationSpec) { if (sliderOnLeft) -it else it } +
                            fadeIn(readerBarsFadeAnimationSpec),
                        exit = slideOutHorizontally(readerBarsSlideAnimationSpec) { if (sliderOnLeft) -it else it } +
                            fadeOut(readerBarsFadeAnimationSpec),
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                ChapterNavigator(
                                    modifier = Modifier.fillMaxHeight(verticalNavigatorHeight),
                                    type = chapterNavigatorType,
                                    onNextChapter = onNextChapter,
                                    enabledNext = enabledNext,
                                    onPreviousChapter = onPreviousChapter,
                                    enabledPrevious = enabledPrevious,
                                    currentPage = currentPage,
                                    totalPages = totalPages,
                                    onPageIndexChange = onPageIndexChange,
                                    onPageIndexChangeFinished = onPageIndexChangeFinished,
                                    pageLabel = pageLabel,
                                    continuousSlider = continuousSlider,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                if (chapterNavigatorType.isHorizontal()) {
                    ChapterNavigator(
                        type = chapterNavigatorType,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        onPageIndexChangeFinished = onPageIndexChangeFinished,
                        pageLabel = pageLabel,
                        continuousSlider = continuousSlider,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    bottomBar()
                }
            }
        }
    }
    }
}

/**
 * Colours the reader bars adopt instead of the app's own chrome.
 *
 * The novel reader paints its page from a chosen paper — cream, sepia, black — and dark chrome
 * bars sitting on cream paper read as a different app bolted on top. The manga reader passes
 * nothing and keeps the surface it always had.
 */
data class ReaderBarSurface(
    val background: Color,
    val content: Color,
    val accent: Color,
)

/**
 * Applies [surface] to everything the bars draw.
 *
 * Only the roles the bars actually read are overridden, so a widget reaching for some other colour
 * still gets a coherent scheme rather than a hole.
 */
@Composable
private fun ReaderBarTheme(surface: ReaderBarSurface?, content: @Composable () -> Unit) {
    if (surface == null) return content()
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            surface = surface.background,
            surfaceVariant = surface.background,
            background = surface.background,
            onSurface = surface.content,
            onSurfaceVariant = surface.content.copy(alpha = 0.72f),
            onBackground = surface.content,
            primary = surface.accent,
            onPrimary = surface.background,
            secondaryContainer = surface.accent.copy(alpha = 0.18f),
            onSecondaryContainer = surface.content,
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
