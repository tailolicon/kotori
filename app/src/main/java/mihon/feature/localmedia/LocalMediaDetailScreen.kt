package mihon.feature.localmedia

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.model.MediaType
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriEmptyState
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriSectionLabel
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.theme.kotori.accent
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import mihon.feature.animeplayer.AnimePlayerActivity
import mihon.feature.mediasource.fetchOnlineText
import mihon.feature.novelreader.NovelReaderActivity

/**
 * Episode/chapter list for a local anime/novel title (design 02/22 episode
 * rows). Tapping an episode opens the video player; a chapter opens the
 * novel reader.
 */
class LocalMediaDetailScreen(
    private val entry: LocalMediaEntry,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val mediaType = entry.type
        val title = entry.title
        val accent = mediaType.accent

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = title,
                    onNavigateUp = navigator::pop,
                    subtitle = when (mediaType) {
                        MediaType.ANIME -> "${entry.files.size} tập · ${entry.sourceName}"
                        else -> "${entry.files.size} chương · ${entry.sourceName}"
                    },
                )
            },
        ) { _ ->
            val files = entry.files
            if (files.isEmpty()) {
                KotoriEmptyState(title = "Không có nội dung")
            } else {
                LazyColumn {
                    itemsIndexed(files, key = { _, f -> f.uri }) { index, file ->
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 4.5.dp)
                                .glass(shape = KotoriShapes.row)
                                .clickable {
                                    scope.launch {
                                        when (mediaType) {
                                            MediaType.ANIME -> context.startActivity(
                                                AnimePlayerActivity.newIntent(
                                                    context = context,
                                                    url = file.uri,
                                                    title = title,
                                                    episodeLabel = "T${index + 1}",
                                                    sourceLabel = entry.sourceName,
                                                ),
                                            )
                                            else -> {
                                                val text = if (file.uri.startsWith("http")) {
                                                    fetchOnlineText(file.uri)
                                                } else {
                                                    readLocalNovelChapter(
                                                        file.uri,
                                                        context.contentResolver,
                                                    )
                                                }
                                                context.startActivity(
                                                    NovelReaderActivity.newIntent(
                                                        context = context,
                                                        title = title,
                                                        chapterLabel = "Chương ${index + 1}",
                                                        text = text,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(86.dp)
                                    .height(52.dp)
                                    .glass(shape = KotoriShapes.thumbSmall, elevated = true),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (mediaType == MediaType.ANIME) "T${index + 1}" else "Ch.${index + 1}",
                                    fontFamily = UnboundedFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = accent.light,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name.substringBeforeLast('.'),
                                    fontFamily = BeVietnamProFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = KotoriColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (mediaType == MediaType.ANIME) "Nhấn để xem" else "Nhấn để đọc",
                                    fontFamily = BeVietnamProFamily,
                                    fontSize = 10.5.sp,
                                    color = KotoriColors.textMuted,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Icon(
                                imageVector = if (mediaType == MediaType.ANIME) {
                                    Icons.Filled.PlayCircle
                                } else {
                                    Icons.Filled.AutoStories
                                },
                                contentDescription = null,
                                tint = accent.end,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Title list shown inside the Library tab for ANIME/NOVEL modes: glass rows
 * with a gradient monogram tile (design 07 source-row style). Non-lazy so
 * multiple sections can stack inside one scroll container.
 */
@Composable
fun LocalMediaColumn(
    entries: List<LocalMediaEntry>,
    mediaType: MediaType,
    onClickEntry: (LocalMediaEntry) -> Unit,
) {
    val accent = mediaType.accent
    Column {
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 4.5.dp)
                    .glass(shape = KotoriShapes.row)
                    .clickable { onClickEntry(entry) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(accent.gradient, KotoriShapes.monogram),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.title.take(1).uppercase(),
                        fontFamily = UnboundedFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = accent.onAccent,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = KotoriColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (mediaType) {
                            MediaType.ANIME -> "${entry.files.size} tập · ${entry.sourceName}"
                            else -> "${entry.files.size} chương · ${entry.sourceName}"
                        },
                        fontFamily = BeVietnamProFamily,
                        fontSize = 10.5.sp,
                        color = KotoriColors.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LocalMediaSectionLabel(mediaType: MediaType) {
    KotoriSectionLabel(
        text = if (mediaType == MediaType.ANIME) "CỤC BỘ · ANIME" else "CỤC BỘ · NOVEL",
        accent = mediaType.accent,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
fun OnlineMediaSectionLabel(mediaType: MediaType) {
    KotoriSectionLabel(
        text = if (mediaType == MediaType.ANIME) "TRỰC TUYẾN · ANIME" else "TRỰC TUYẾN · NOVEL",
        accent = mediaType.accent,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
    )
}
