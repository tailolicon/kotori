package eu.kanade.presentation.history.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.GradientCircleButton
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Kotori glass history row: thumb 48×66, title/meta, optional add-to-library
 * heart, delete, and a gradient circular resume button.
 */
@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    Row(
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 4.5.dp)
            .glass(shape = KotoriShapes.row)
            .clickable(onClick = onClickResume)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = history.coverData,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(48.dp)
                .height(66.dp)
                .clip(KotoriShapes.thumbSmall)
                .clickable(onClick = onClickCover),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = history.title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val readAt = remember { history.readAt?.toTimestampString() ?: "" }
            Text(
                text = if (history.chapterNumber > -1) {
                    stringResource(
                        MR.strings.recent_manga_time,
                        formatChapterNumber(history.chapterNumber),
                        readAt,
                    )
                } else {
                    readAt
                },
                modifier = Modifier.padding(top = 3.dp),
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!history.coverData.isMangaFavorite) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(34.dp)
                    .glass(shape = CircleShape, elevated = true)
                    .clickable(onClick = onClickFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = stringResource(MR.strings.add_to_library),
                    tint = accent.end,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = KotoriColors.textFaint,
                modifier = Modifier.size(19.dp),
            )
        }

        GradientCircleButton(
            icon = Icons.Filled.AutoStories,
            contentDescription = stringResource(MR.strings.action_resume),
            onClick = onClickResume,
            accent = accent,
            size = 38.dp,
            iconSize = 20.dp,
        )
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                onClickCover = {},
                onClickResume = {},
                onClickDelete = {},
                onClickFavorite = {},
            )
        }
    }
}
