package mihon.feature.novelreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.presentation.core.screens.LoadingScreen

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
            onProgressChanged = viewModel::onProgress,
        )
        else -> NovelReaderScreen(
            title = state.manga?.title.orEmpty(),
            chapterLabel = state.chapter?.name.orEmpty(),
            content = state.content,
            startPercent = state.startPercent,
            onProgressChanged = viewModel::onProgress,
            preferences = preferences,
            onNavigateUp = onNavigateUp,
        )
    }
}
