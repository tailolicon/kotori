package mihon.feature.animeextension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriEmptyState
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.launch
import mihon.feature.animeplayer.AnimePlayerActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private fun catalogueSource(id: Long): AnimeCatalogueSource? =
    Injekt.get<AnimeExtensionManager>().getSource(id) as? AnimeCatalogueSource

/** Popular catalog grid of one anime source (design 18). */
class AnimeSourceCatalogScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = remember(sourceId) { catalogueSource(sourceId) }
        val animeList by produceState(initialValue = emptyList<SAnime>(), source) {
            value = runCatching { source?.getPopularAnime(1)?.animes ?: emptyList() }
                .getOrDefault(emptyList())
        }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = source?.name ?: "Nguồn",
                    subtitle = "Phổ biến · ${animeList.size} bộ",
                    onNavigateUp = navigator::pop,
                )
            },
        ) { _ ->
            if (animeList.isEmpty()) {
                KotoriEmptyState(title = "Đang tải hoặc nguồn trống…")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    gridItems(animeList, key = { it.url }) { anime ->
                        Column(
                            modifier = Modifier.clickable {
                                navigator.push(AnimeDetailScreen(sourceId, anime.url, anime.title, anime.thumbnail_url))
                            },
                        ) {
                            AsyncImage(
                                model = anime.thumbnail_url,
                                contentDescription = anime.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(KotoriShapes.browseCover)
                                    .background(KotoriColors.glassBg),
                            )
                            Text(
                                text = anime.title,
                                fontFamily = BeVietnamProFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = KotoriColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Episode list of one anime; taps resolve videos and open the player. */
class AnimeDetailScreen(
    private val sourceId: Long,
    private val animeUrl: String,
    private val animeTitle: String,
    private val thumbnailUrl: String?,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val source = remember(sourceId) { catalogueSource(sourceId) }

        val anime = remember {
            SAnime.create().apply {
                url = animeUrl
                title = animeTitle
                thumbnail_url = thumbnailUrl
            }
        }
        val episodes by produceState(initialValue = emptyList<SEpisode>(), source) {
            value = runCatching { source?.getEpisodeList(anime) ?: emptyList() }
                .getOrDefault(emptyList())
        }
        var resolvingFor by remember { mutableStateOf<SEpisode?>(null) }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = animeTitle,
                    subtitle = "${episodes.size} tập · ${source?.name.orEmpty()}",
                    onNavigateUp = navigator::pop,
                )
            },
        ) { _ ->
            if (episodes.isEmpty()) {
                KotoriEmptyState(title = "Đang tải hoặc chưa có tập…")
            } else {
                LazyColumn {
                    items(episodes, key = { it.url }) { episode ->
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 4.5.dp)
                                .glass(shape = KotoriShapes.row)
                                .clickable { resolvingFor = episode }
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = episode.name,
                                fontFamily = BeVietnamProFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = KotoriColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Filled.PlayCircle,
                                contentDescription = null,
                                tint = AnimeAccent.end,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }

        val currentEpisode = resolvingFor
        if (currentEpisode != null) {
            val videos by produceState(
                initialValue = emptyList<eu.kanade.tachiyomi.animesource.model.Video>(),
                currentEpisode,
            ) {
                value = runCatching {
                    source?.getVideoList(currentEpisode) ?: emptyList()
                }.getOrDefault(emptyList())
            }

            AlertDialog(
                onDismissRequest = { resolvingFor = null },
                title = { Text(currentEpisode.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    when {
                        videos.isEmpty() -> Text("Đang lấy video…")
                        else -> LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(videos) { video ->
                                Text(
                                    text = video.quality.ifEmpty { video.videoUrl },
                                    fontFamily = BeVietnamProFamily,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val url = video.videoUrl.ifEmpty { return@clickable }
                                            context.startActivity(
                                                AnimePlayerActivity.newIntent(
                                                    context = context,
                                                    url = url,
                                                    title = animeTitle,
                                                    episodeLabel = currentEpisode.name,
                                                    sourceLabel = source?.name,
                                                ),
                                            )
                                            resolvingFor = null
                                        }
                                        .padding(vertical = 10.dp),
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { resolvingFor = null }) { Text("Đóng") }
                },
            )
        }
    }
}
