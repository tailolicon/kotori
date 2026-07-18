package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.extension.animeExtensionsTab
import eu.kanade.tachiyomi.ui.browse.anime.source.animeSourcesTab
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.anime.migration.sources.migrateAnimeSourceTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.mediasource.MediaBrowseContent
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val activeMode by uiPreferences.activeMediaMode.changes()
            .collectAsState(initial = uiPreferences.activeMediaMode.get())
        if (activeMode == MediaType.ANIME) {
            // Anime mode: full Aniyomi-style browse (sources, extensions, migrate).
            val animeExtensionsScreenModel = rememberScreenModel { AnimeExtensionsScreenModel() }
            val animeExtensionsState by animeExtensionsScreenModel.state.collectAsState()
            val animeTabs = listOf(
                animeSourcesTab(),
                animeExtensionsTab(animeExtensionsScreenModel),
                migrateAnimeSourceTab(),
            )
            val animeState = rememberPagerState { animeTabs.size }
            TabbedScreen(
                titleRes = MR.strings.browse,
                tabs = animeTabs,
                state = animeState,
                searchQuery = animeExtensionsState.searchQuery,
                onChangeSearchQuery = animeExtensionsScreenModel::search,
            )
            LaunchedEffect(Unit) {
                switchToExtensionTabChannel.receiveAsFlow()
                    .collectLatest { animeState.scrollToPage(1) }
            }
            LaunchedEffect(Unit) {
                (context as? MainActivity)?.ready = true
            }
            return
        }
        if (activeMode == MediaType.NOVEL) {
            // Novels are all built-in sources — there is no installable-novel-extension ecosystem —
            // so Browse shows only Sources, not the manga Extensions/Migrate tabs (which would list
            // manga extensions since they share this screen).
            val novelTabs = listOf(sourcesTab())
            val novelState = rememberPagerState { novelTabs.size }
            TabbedScreen(
                titleRes = MR.strings.browse,
                tabs = novelTabs,
                state = novelState,
            )
            LaunchedEffect(Unit) {
                (context as? MainActivity)?.ready = true
            }
            return
        }

        // Manga mode: full browse (sources, extensions, migrate).

        // Hoisted for extensions tab's search bar
        val extensionsScreenModel = rememberScreenModel { ExtensionsScreenModel() }
        val extensionsState by extensionsScreenModel.state.collectAsState()

        val tabs = listOf(
            sourcesTab(),
            extensionsTab(extensionsScreenModel),
            migrateSourceTab(),
        )

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            searchQuery = extensionsState.searchQuery,
            onChangeSearchQuery = extensionsScreenModel::search,
        )
        LaunchedEffect(Unit) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest { state.scrollToPage(1) }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
