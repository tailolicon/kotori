package eu.kanade.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriEmptyState
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSelectionBar
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

@Composable
fun UpdateScreen(
    state: UpdatesScreenModel.State,
    snackbarHostState: SnackbarHostState,
    lastUpdated: Long,
    onClickCover: (UpdatesItem) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onCalendarClicked: () -> Unit,
    onUpdateLibrary: () -> Boolean,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onOpenChapter: (UpdatesItem) -> Unit,
    onFilterClicked: () -> Unit,
    hasActiveFilters: Boolean,
) {
    BackHandler(enabled = state.selectionMode) {
        onSelectAll(false)
    }

    KotoriScreenScaffold(
        header = {
            KotoriHeader(
                title = stringResource(MR.strings.label_recent_updates),
                subtitle = stringResource(
                    MR.strings.updates_last_update_info,
                    relativeTimeSpanString(lastUpdated),
                ),
                actions = {
                    KotoriHeaderAction(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = stringResource(MR.strings.action_update_library),
                        onClick = { onUpdateLibrary() },
                    )
                    KotoriHeaderAction(
                        icon = Icons.Outlined.CalendarMonth,
                        contentDescription = stringResource(MR.strings.action_view_upcoming),
                        onClick = onCalendarClicked,
                    )
                    KotoriHeaderAction(
                        icon = Icons.Filled.FilterAlt,
                        contentDescription = stringResource(MR.strings.action_filter),
                        onClick = onFilterClicked,
                        tint = if (hasActiveFilters) {
                            KotoriTheme.accent.light
                        } else {
                            KotoriColors.textPrimary.copy(alpha = 0.85f)
                        },
                    )
                },
            )
        },
        bottomBar = {
            if (state.selectionMode) {
                KotoriSelectionBar(count = state.selected.size) {
                    KotoriHeaderAction(
                        icon = Icons.Outlined.SelectAll,
                        contentDescription = stringResource(MR.strings.action_select_all),
                        onClick = { onSelectAll(true) },
                    )
                    KotoriHeaderAction(
                        icon = Icons.Outlined.FlipToBack,
                        contentDescription = stringResource(MR.strings.action_select_inverse),
                        onClick = onInvertSelection,
                    )
                    if (state.selected.fastAny { !it.update.bookmark }) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.Bookmark,
                            contentDescription = stringResource(MR.strings.action_bookmark),
                            onClick = { onMultiBookmarkClicked(state.selected, true) },
                        )
                    }
                    if (state.selected.fastAll { it.update.bookmark }) {
                        KotoriHeaderAction(
                            icon = Icons.Outlined.BookmarkRemove,
                            contentDescription = stringResource(MR.strings.action_remove_bookmark),
                            onClick = { onMultiBookmarkClicked(state.selected, false) },
                        )
                    }
                    if (state.selected.fastAny { !it.update.read }) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.DoneAll,
                            contentDescription = stringResource(MR.strings.action_mark_as_read),
                            onClick = { onMultiMarkAsReadClicked(state.selected, true) },
                        )
                    }
                    if (state.selected.fastAny { it.update.read || it.update.lastPageRead > 0L }) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.RemoveDone,
                            contentDescription = stringResource(MR.strings.action_mark_as_unread),
                            onClick = { onMultiMarkAsReadClicked(state.selected, false) },
                        )
                    }
                    if (state.selected.fastAny { it.downloadStateProvider() != Download.State.DOWNLOADED }) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.Download,
                            contentDescription = stringResource(MR.strings.manga_download),
                            onClick = { onDownloadChapter(state.selected, ChapterDownloadAction.START) },
                        )
                    }
                    if (state.selected.fastAny { it.downloadStateProvider() == Download.State.DOWNLOADED }) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            onClick = { onMultiDeleteClicked(state.selected) },
                            tint = KotoriColors.danger,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.items.isEmpty() -> KotoriEmptyState(
                title = "Chưa có cập nhật",
                hint = "Kéo xuống để cập nhật thư viện",
                modifier = Modifier.padding(contentPadding),
            )
            else -> {
                val scope = rememberCoroutineScope()
                var isRefreshing by remember { mutableStateOf(false) }

                PullRefresh(
                    refreshing = isRefreshing,
                    onRefresh = {
                        val started = onUpdateLibrary()
                        if (!started) return@PullRefresh
                        scope.launch {
                            // Fake refresh status but hide it after a second as it's a long running task
                            isRefreshing = true
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                    enabled = !state.selectionMode,
                    indicatorPadding = contentPadding,
                ) {
                    FastScrollLazyColumn(
                        contentPadding = contentPadding,
                    ) {
                        updatesUiItems(
                            uiModels = state.getUiModel(),
                            selectionMode = state.selectionMode,
                            onUpdateSelected = onUpdateSelected,
                            onClickCover = onClickCover,
                            onClickUpdate = onOpenChapter,
                            onDownloadChapter = onDownloadChapter,
                        )
                    }
                }
            }
        }
    }
}

sealed interface UpdatesUiModel {
    data class Header(val date: LocalDate) : UpdatesUiModel
    data class Item(val item: UpdatesItem) : UpdatesUiModel
}
