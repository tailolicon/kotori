package eu.kanade.tachiyomi.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.download.anime.AnimeDownloadQueueScreen as AnimeDownloadQueueContent
import eu.kanade.tachiyomi.ui.download.anime.AnimeDownloadQueueScreenModel
import eu.kanade.tachiyomi.ui.stats.anime.animeStatsTab
import tachiyomi.i18n.aniyomi.AYMR

/**
 * Standalone Voyager screen hosting the Aniyomi anime download queue,
 * used from the ANIME-mode More tab.
 */
object AnimeDownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { AnimeDownloadQueueScreenModel() }
        val downloadList by screenModel.state.collectAsState()
        AnimeDownloadQueueContent(
            contentPadding = PaddingValues(),
            scope = scope,
            screenModel = screenModel,
            downloadList = downloadList,
            nestedScrollConnection = object : NestedScrollConnection {},
        )
    }
}

/**
 * Standalone Voyager screen hosting the Aniyomi anime statistics.
 */
class AnimeStatsScreen : Screen() {

    @Composable
    override fun Content() {
        TabbedScreen(
            titleRes = AYMR.strings.label_anime,
            tabs = listOf(animeStatsTab()),
        )
    }
}
