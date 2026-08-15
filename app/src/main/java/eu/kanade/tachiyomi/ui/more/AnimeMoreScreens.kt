package eu.kanade.tachiyomi.ui.more

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.theme.kotori.AuroraBackground
import eu.kanade.presentation.theme.kotori.GradientButton
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.download.anime.AnimeDownloadQueueScreen as AnimeDownloadQueueContent
import eu.kanade.tachiyomi.ui.download.anime.AnimeDownloadQueueScreenModel
import eu.kanade.tachiyomi.ui.stats.anime.animeStatsTab
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Standalone Voyager screen hosting the Aniyomi anime download queue,
 * used from the ANIME-mode More tab.
 *
 * Wears the same frame as the manga queue — aurora, title with the pending count, a way back
 * and the pause/resume button — so switching mode does not drop the user onto a bare list.
 */
object AnimeDownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { AnimeDownloadQueueScreenModel() }
        val downloadList by screenModel.state.collectAsState()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.subItems.size } }
        }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

        AuroraBackground {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    AppBar(
                        titleContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(MR.strings.label_download_queue),
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f, false),
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (downloadCount > 0) {
                                    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                    Pill(
                                        text = "$downloadCount",
                                        modifier = Modifier.padding(start = 4.dp),
                                        color = MaterialTheme.colorScheme.onBackground
                                            .copy(alpha = pillAlpha),
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        },
                        navigateUp = navigator::pop,
                        actions = {
                            if (downloadList.isNotEmpty()) {
                                var sortExpanded by remember { mutableStateOf(false) }
                                DropdownMenu(
                                    expanded = sortExpanded,
                                    onDismissRequest = { sortExpanded = false },
                                ) {
                                    NestedMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                        children = { closeMenu ->
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_newest)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.episode.dateUpload },
                                                        true,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.episode.dateUpload },
                                                        false,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                        },
                                    )
                                    NestedMenuItem(
                                        text = {
                                            Text(text = stringResource(AYMR.strings.action_order_by_episode_number))
                                        },
                                        children = { closeMenu ->
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_asc)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.episode.episodeNumber },
                                                        false,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_desc)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.episode.episodeNumber },
                                                        true,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                        },
                                    )
                                }
                                AppBarActions(
                                    listOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_sort),
                                            icon = Icons.AutoMirrored.Outlined.Sort,
                                            onClick = { sortExpanded = true },
                                        ),
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_cancel_all),
                                            onClick = { screenModel.clearQueue() },
                                        ),
                                    ),
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                },
                floatingActionButton = {
                    val isRunning by screenModel.isDownloaderRunning.collectAsState(initial = false)
                    val accent = KotoriTheme.accent
                    GradientButton(
                        onClick = {
                            if (isRunning) {
                                screenModel.pauseDownloads()
                            } else {
                                screenModel.startDownloads()
                            }
                        },
                        modifier = Modifier.animateFloatingActionButton(
                            visible = downloadList.isNotEmpty(),
                            alignment = Alignment.BottomEnd,
                        ),
                    ) {
                        val icon = if (isRunning) Icons.Outlined.Pause else Icons.Filled.PlayArrow
                        Icon(imageVector = icon, contentDescription = null, tint = accent.onAccent)
                        Text(
                            text = stringResource(if (isRunning) MR.strings.action_pause else MR.strings.action_resume),
                            color = accent.onAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                },
            ) { contentPadding ->
                AnimeDownloadQueueContent(
                    contentPadding = contentPadding,
                    scope = scope,
                    screenModel = screenModel,
                    downloadList = downloadList,
                    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                )
            }
        }
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
