package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.components.DownloadDropdownMenu
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Kotori detail toolbar: floating glass circle buttons over the key visual.
 * Title + translucent bar fade in as the list scrolls (providers preserved).
 */
@Composable
fun MangaToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickFilter: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickRefresh: () -> Unit,
    onClickMigrate: (() -> Unit)?,
    onClickEditNotes: () -> Unit,

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val isActionMode = actionModeCounter > 0
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                KotoriColors.bgNavbar.copy(
                    alpha = if (isActionMode) 0.75f else 0.75f * backgroundAlphaProvider(),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isActionMode) {
                KotoriHeaderAction(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(MR.strings.action_cancel),
                    onClick = onCancelActionMode,
                )
                Text(
                    text = actionModeCounter.toString(),
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = KotoriTheme.accent.light,
                    modifier = Modifier.weight(1f),
                )
                KotoriHeaderAction(
                    icon = Icons.Outlined.SelectAll,
                    contentDescription = stringResource(MR.strings.action_select_all),
                    onClick = onSelectAll,
                )
                KotoriHeaderAction(
                    icon = Icons.Outlined.FlipToBack,
                    contentDescription = stringResource(MR.strings.action_select_inverse),
                    onClick = onInvertSelection,
                )
            } else {
                KotoriHeaderAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    onClick = navigateUp,
                )
                Text(
                    text = title,
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = KotoriColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(titleAlphaProvider()),
                )
                if (onClickDownload != null) {
                    Box {
                        var downloadExpanded by remember { mutableStateOf(false) }
                        KotoriHeaderAction(
                            icon = Icons.Outlined.Download,
                            contentDescription = stringResource(MR.strings.manga_download),
                            onClick = { downloadExpanded = !downloadExpanded },
                        )
                        DownloadDropdownMenu(
                            expanded = downloadExpanded,
                            onDismissRequest = { downloadExpanded = false },
                            onDownloadClicked = onClickDownload,
                        )
                    }
                }
                KotoriHeaderAction(
                    icon = Icons.Outlined.FilterList,
                    contentDescription = stringResource(MR.strings.action_filter),
                    onClick = onClickFilter,
                    tint = if (hasFilters) {
                        KotoriTheme.accent.light
                    } else {
                        KotoriColors.textPrimary.copy(alpha = 0.85f)
                    },
                )
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    KotoriHeaderAction(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = null,
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_webview_refresh)) },
                            onClick = {
                                menuOpen = false
                                onClickRefresh()
                            },
                        )
                        if (onClickEditCategory != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_edit_categories)) },
                                onClick = {
                                    menuOpen = false
                                    onClickEditCategory()
                                },
                            )
                        }
                        if (onClickMigrate != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_migrate)) },
                                onClick = {
                                    menuOpen = false
                                    onClickMigrate()
                                },
                            )
                        }
                        if (onClickShare != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_share)) },
                                onClick = {
                                    menuOpen = false
                                    onClickShare()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_notes)) },
                            onClick = {
                                menuOpen = false
                                onClickEditNotes()
                            },
                        )
                    }
                }
            }
        }
    }
}
