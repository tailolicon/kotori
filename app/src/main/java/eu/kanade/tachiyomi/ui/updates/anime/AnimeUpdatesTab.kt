package eu.kanade.tachiyomi.ui.updates.anime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.entries.anime.EpisodeOptionsDialogScreen
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.presentation.updates.anime.AnimeUpdateScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import kotlinx.coroutines.flow.collectLatest
import mihon.feature.upcoming.anime.UpcomingAnimeScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

@Composable
fun Screen.AnimeUpdatesHome() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { AnimeUpdatesScreenModel() }
    val settingsScreenModel = rememberScreenModel { UpdatesSettingsScreenModel() }
    val scope = rememberCoroutineScope()
    val state by screenModel.state.collectAsState()

    suspend fun openEpisode(updateItem: AnimeUpdatesItem, altPlayer: Boolean = false) {
        val playerPreferences: PlayerPreferences by injectLazy()
        val update = updateItem.update
        val extPlayer = playerPreferences.alwaysUseExternalPlayer().get() != altPlayer
        MainActivity.startPlayerActivity(context, update.animeId, update.episodeId, extPlayer)
    }

    AnimeUpdateScreen(
        state = state,
        snackbarHostState = screenModel.snackbarHostState,
        lastUpdated = screenModel.lastUpdated,
        onClickCover = { item -> navigator.push(AnimeScreen(item.update.animeId)) },
        onSelectAll = screenModel::toggleAllSelection,
        onInvertSelection = screenModel::invertSelection,
        onCalendarClicked = { navigator.push(UpcomingAnimeScreen()) },
        onUpdateLibrary = screenModel::updateLibrary,
        onDownloadEpisode = screenModel::downloadEpisodes,
        onMultiBookmarkClicked = screenModel::bookmarkUpdates,
        onMultiFillermarkClicked = screenModel::fillermarkUpdates,
        onMultiMarkAsSeenClicked = screenModel::markUpdatesSeen,
        onMultiDeleteClicked = screenModel::showConfirmDeleteEpisodes,
        onUpdateSelected = screenModel::toggleSelection,
        onOpenEpisode = { updateItem: AnimeUpdatesItem, altPlayer: Boolean ->
            scope.launchIO { openEpisode(updateItem, altPlayer) }
            Unit
        },
        onFilterClicked = screenModel::showFilterDialog,
        hasActiveFilters = state.hasActiveFilters,
    )

    val onDismissDialog = { screenModel.setDialog(null) }
    when (val dialog = state.dialog) {
        is AnimeUpdatesScreenModel.Dialog.DeleteConfirmation -> {
            UpdatesDeleteConfirmationDialog(
                onDismissRequest = onDismissDialog,
                onConfirm = { screenModel.deleteEpisodes(dialog.toDelete) },
                isManga = false,
            )
        }
        is AnimeUpdatesScreenModel.Dialog.FilterSheet -> {
            UpdatesFilterDialog(
                onDismissRequest = onDismissDialog,
                screenModel = settingsScreenModel,
            )
        }
        is AnimeUpdatesScreenModel.Dialog.ShowQualities -> {
            EpisodeOptionsDialogScreen.onDismissDialog = onDismissDialog
            NavigatorAdaptiveSheet(
                screen = EpisodeOptionsDialogScreen(
                    useExternalDownloader = screenModel.useExternalDownloader,
                    episodeTitle = dialog.episodeTitle,
                    episodeId = dialog.episodeId,
                    animeId = dialog.animeId,
                    sourceId = dialog.sourceId,
                ),
                onDismissRequest = onDismissDialog,
            )
        }
        null -> {}
    }

    LaunchedEffect(Unit) {
        screenModel.events.collectLatest { event ->
            when (event) {
                AnimeUpdatesScreenModel.Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.internal_error),
                )
                is AnimeUpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                    val msg = if (event.started) {
                        MR.strings.updating_library
                    } else {
                        MR.strings.update_already_running
                    }
                    screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                }
            }
        }
    }

    DisposableEffect(Unit) {
        screenModel.resetNewUpdatesCount()
        onDispose { screenModel.resetNewUpdatesCount() }
    }
}
