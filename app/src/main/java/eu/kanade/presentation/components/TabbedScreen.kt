package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSearchField
import eu.kanade.presentation.theme.kotori.KotoriSegmentRow
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TabbedScreen(
    titleRes: StringResource,
    tabs: List<TabContent>,
    state: PagerState = rememberPagerState { tabs.size },
    searchQuery: String? = null,
    onChangeSearchQuery: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    KotoriScreenScaffold(
        header = {
            val tab = tabs[state.currentPage]
            val searchEnabled = tab.searchEnabled

            KotoriHeader(
                title = stringResource(titleRes),
                actions = {
                    if (searchEnabled) {
                        KotoriHeaderAction(
                            icon = Icons.Filled.Search,
                            contentDescription = null,
                            onClick = {
                                if (searchQuery == null) onChangeSearchQuery("") else onChangeSearchQuery(null)
                            },
                        )
                    }
                    val iconActions = tab.actions.filterIsInstance<AppBar.Action>()
                    val overflowActions = tab.actions.filterIsInstance<AppBar.OverflowAction>()
                    iconActions.forEach { action ->
                        KotoriHeaderAction(
                            icon = action.icon,
                            contentDescription = action.title,
                            onClick = action.onClick,
                        )
                    }
                    if (overflowActions.isNotEmpty()) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            KotoriHeaderAction(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = null,
                                onClick = { menuOpen = true },
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                overflowActions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.title) },
                                        onClick = {
                                            menuOpen = false
                                            action.onClick()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
            if (searchEnabled && searchQuery != null) {
                KotoriSearchField(
                    value = searchQuery,
                    onValueChange = onChangeSearchQuery,
                    placeholder = "Tìm trên mọi nguồn…",
                    autoFocus = true,
                    gradientBorder = true,
                    onClear = { onChangeSearchQuery("") },
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp),
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        Column {
            KotoriSegmentRow(
                labels = tabs.map { stringResource(it.titleRes) },
                badges = tabs.map { it.badgeNumber },
                activeIndex = state.currentPage,
                onSelect = { scope.launch { state.animateScrollToPage(it) } },
                modifier = Modifier
                    .zIndex(1f)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )

            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = state,
                verticalAlignment = Alignment.Top,
            ) { page ->
                tabs[page].content(
                    contentPadding,
                    snackbarHostState,
                )
            }
        }
    }
}

data class TabContent(
    val titleRes: StringResource,
    val badgeNumber: Int? = null,
    val searchEnabled: Boolean = false,
    val actions: List<AppBar.AppBarAction> = listOf(),
    val content: @Composable (contentPadding: PaddingValues, snackbarHostState: SnackbarHostState) -> Unit,
)
