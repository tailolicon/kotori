package eu.kanade.presentation.updates.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadIndicator
import eu.kanade.presentation.entries.components.DotSeparatorText
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.ui.updates.anime.AnimeUpdatesItem
import tachiyomi.domain.updates.anime.model.AnimeUpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.TimeUnit

internal fun LazyListScope.animeUpdatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "animeUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = 18.dp, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
                fontFamily = BeVietnamProFamily,
                fontSize = 11.sp,
                color = KotoriColors.textMuted,
            )
        }
    }
}

internal fun LazyListScope.animeUpdatesUiItems(
    uiModels: List<AnimeUpdatesUiModel>,
    selectionMode: Boolean,
    onUpdateSelected: (AnimeUpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onClickCover: (AnimeUpdatesItem) -> Unit,
    onClickUpdate: (AnimeUpdatesItem, altPlayer: Boolean) -> Unit,
    onDownloadEpisode: (List<AnimeUpdatesItem>, EpisodeDownloadAction) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is AnimeUpdatesUiModel.Header -> "header"
                is AnimeUpdatesUiModel.Item -> "item"
            }
        },
        key = {
            when (it) {
                is AnimeUpdatesUiModel.Header -> "animeUpdatesHeader-${it.hashCode()}"
                is AnimeUpdatesUiModel.Item -> "animeUpdates-${it.item.update.animeId}-${it.item.update.episodeId}"
            }
        },
    ) { item ->
        when (item) {
            is AnimeUpdatesUiModel.Header -> {
                KotoriSectionLabel(
                    text = relativeDateText(item.date),
                    modifier = Modifier
                        .animateItemFastScroll()
                        .padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            is AnimeUpdatesUiModel.Item -> {
                val updatesItem = item.item
                AnimeUpdatesUiItem(
                    modifier = Modifier.animateItemFastScroll(),
                    update = updatesItem.update,
                    selected = updatesItem.selected,
                    watchProgress = updatesItem.update.lastSecondSeen
                        .takeIf { !updatesItem.update.seen && it > 0L }
                        ?.let {
                            stringResource(
                                AYMR.strings.episode_progress,
                                formatProgress(it),
                                formatProgress(updatesItem.update.totalSeconds),
                            )
                        },
                    onLongClick = {
                        onUpdateSelected(updatesItem, !updatesItem.selected, true, true)
                    },
                    onClick = {
                        when {
                            selectionMode -> onUpdateSelected(
                                updatesItem,
                                !updatesItem.selected,
                                true,
                                false,
                            )
                            else -> onClickUpdate(updatesItem, false)
                        }
                    },
                    onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
                    onDownloadEpisode = { action: EpisodeDownloadAction ->
                        onDownloadEpisode(listOf(updatesItem), action)
                    }.takeIf { !selectionMode },
                    downloadStateProvider = updatesItem.downloadStateProvider,
                    downloadProgressProvider = updatesItem.downloadProgressProvider,
                )
            }
        }
    }
}

@Composable
private fun AnimeUpdatesUiItem(
    update: AnimeUpdatesWithRelations,
    selected: Boolean,
    watchProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadEpisode: ((EpisodeDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> AnimeDownload.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = KotoriTheme.accent
    val textAlpha = if (update.seen) DISABLED_ALPHA else 1f

    Row(
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 4.5.dp)
            .then(
                if (selected) {
                    Modifier
                        .clip(KotoriShapes.row)
                        .background(accent.start.copy(alpha = 0.16f))
                        .border(1.dp, accent.light.copy(alpha = 0.55f), KotoriShapes.row)
                } else {
                    Modifier.glass(shape = KotoriShapes.row)
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(end = 11.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accent.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = accent.onAccent,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        AsyncImage(
            model = update.coverData,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(46.dp)
                .height(62.dp)
                .clip(KotoriShapes.thumbSmall)
                .then(
                    if (onClickCover != null) {
                        Modifier.combinedClickable(onClick = onClickCover)
                    } else {
                        Modifier
                    },
                ),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 11.dp)
                .weight(1f),
        ) {
            Text(
                text = update.animeTitle,
                maxLines = 1,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                color = KotoriColors.textPrimary.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp),
            ) {
                var textHeight by remember { mutableIntStateOf(0) }
                if (!update.seen) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = accent.light,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                        tint = accent.light,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                if (update.fillermark) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = stringResource(AYMR.strings.action_filter_fillermarked),
                        modifier = Modifier
                            .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = update.episodeName,
                    maxLines = 1,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 10.5.sp,
                    color = KotoriColors.textMuted.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textHeight = it.size.height },
                    modifier = Modifier
                        .weight(weight = 1f, fill = false),
                )
                if (watchProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = watchProgress,
                        maxLines = 1,
                        fontFamily = BeVietnamProFamily,
                        fontSize = 10.5.sp,
                        color = KotoriColors.textFaint,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(34.dp)
                .glass(shape = CircleShape, elevated = true),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides KotoriColors.textSecondary) {
                EpisodeDownloadIndicator(
                    enabled = onDownloadEpisode != null,
                    downloadStateProvider = downloadStateProvider,
                    downloadProgressProvider = downloadProgressProvider,
                    onClick = { onDownloadEpisode?.invoke(it) },
                )
            }
        }
    }
}

private fun formatProgress(milliseconds: Long): String {
    return if (milliseconds > 3600000L) {
        String.format(
            "%d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(milliseconds),
            TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    } else {
        String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(milliseconds),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    }
}
