package mihon.feature.stremio

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.theme.kotori.AnimeAccent
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriEmptyState
import eu.kanade.presentation.theme.kotori.KotoriHeader
import eu.kanade.presentation.theme.kotori.KotoriHeaderAction
import eu.kanade.presentation.theme.kotori.KotoriScreenScaffold
import eu.kanade.presentation.theme.kotori.KotoriShapes
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.presentation.theme.kotori.glass
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.feature.animeplayer.AnimePlayerActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Stremio addon hub (high-quality stream sources). User adds addon manifest
 * URLs (e.g. debrid-backed addons); catalogs browse into metas, episodes and
 * playable http streams (design 17/18 layouts).
 */
class StremioHubScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { Injekt.get<SourcePreferences>() }
        val addons by preferences.stremioAddons.changes()
            .collectAsState(initial = preferences.stremioAddons.get())
        var showAddDialog by remember { mutableStateOf(false) }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = "Stremio",
                    subtitle = "Nguồn stream chất lượng cao qua giao thức addon",
                    onNavigateUp = navigator::pop,
                    actions = {
                        KotoriHeaderAction(
                            icon = Icons.Filled.Add,
                            contentDescription = "Thêm addon",
                            onClick = { showAddDialog = true },
                        )
                    },
                )
            },
        ) { _ ->
            if (addons.isEmpty()) {
                KotoriEmptyState(
                    title = "Chưa có addon",
                    hint = "Bấm + và dán URL manifest.json của addon Stremio " +
                        "(ví dụ addon debrid trả link phát trực tiếp)",
                )
            } else {
                LazyColumn {
                    items(addons.toList(), key = { it }) { manifestUrl ->
                        StremioAddonRow(
                            manifestUrl = manifestUrl,
                            onOpen = { navigator.push(StremioCatalogListScreen(manifestUrl)) },
                            onRemove = {
                                preferences.stremioAddons.set(addons - manifestUrl)
                            },
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            var input by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Thêm addon Stremio") },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("https://…/manifest.json") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val url = input.trim()
                            if (url.startsWith("http") && url.endsWith("manifest.json")) {
                                preferences.stremioAddons.set(addons + url)
                            }
                            showAddDialog = false
                        },
                    ) { Text("Thêm") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Hủy") }
                },
            )
        }
    }
}

@Composable
private fun StremioAddonRow(
    manifestUrl: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val manifest by produceState<StremioClient.Manifest?>(initialValue = null, manifestUrl) {
        value = runCatching { StremioClient.fetchManifest(manifestUrl) }.getOrNull()
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.5.dp)
            .glass(shape = KotoriShapes.row)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(AnimeAccent.gradient, KotoriShapes.monogram),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (manifest?.name ?: "?").take(1).uppercase(),
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = AnimeAccent.onAccent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manifest?.name ?: "Đang tải…",
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = KotoriColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = manifest?.types?.joinToString(" · ")?.uppercase() ?: manifestUrl,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Xoá",
            tint = KotoriColors.danger,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onRemove),
        )
    }
}

/** Catalog list of one addon. */
class StremioCatalogListScreen(private val manifestUrl: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manifest by produceState<StremioClient.Manifest?>(initialValue = null) {
            value = runCatching { StremioClient.fetchManifest(manifestUrl) }.getOrNull()
        }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = manifest?.name ?: "Addon",
                    subtitle = "Chọn danh mục",
                    onNavigateUp = navigator::pop,
                )
            },
        ) { _ ->
            val catalogs = manifest?.catalogs.orEmpty()
            if (catalogs.isEmpty()) {
                KotoriEmptyState(
                    title = if (manifest == null) "Đang tải…" else "Addon không có danh mục",
                    hint = if (manifest != null) {
                        "Addon chỉ-stream (như debrid) không có catalog — dùng kèm addon có catalog"
                    } else {
                        null
                    },
                )
            } else {
                LazyColumn {
                    items(catalogs, key = { it.type + it.id }) { catalog ->
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 4.5.dp)
                                .glass(shape = KotoriShapes.row)
                                .clickable {
                                    navigator.push(StremioCatalogScreen(manifestUrl, catalog))
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = catalog.name ?: catalog.id,
                                    fontFamily = BeVietnamProFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = KotoriColors.textPrimary,
                                )
                                Text(
                                    text = catalog.type.uppercase(),
                                    fontFamily = BeVietnamProFamily,
                                    fontSize = 10.5.sp,
                                    color = KotoriColors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Poster grid of a catalog (design 18). */
class StremioCatalogScreen(
    private val manifestUrl: String,
    private val catalog: StremioClient.Catalog,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val metas by produceState(initialValue = emptyList<StremioClient.Meta>()) {
            value = runCatching { StremioClient.fetchCatalog(manifestUrl, catalog) }
                .getOrDefault(emptyList())
        }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = catalog.name ?: catalog.id,
                    subtitle = "${metas.size} mục · ${catalog.type}",
                    onNavigateUp = navigator::pop,
                )
            },
        ) { _ ->
            if (metas.isEmpty()) {
                KotoriEmptyState(title = "Đang tải hoặc danh mục trống…")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp,
                        vertical = 8.dp,
                    ),
                ) {
                    gridItems(metas, key = { it.id }) { meta ->
                        Column(
                            modifier = Modifier.clickable {
                                navigator.push(StremioDetailScreen(manifestUrl, catalog.type, meta.id))
                            },
                        ) {
                            AsyncImage(
                                model = meta.poster,
                                contentDescription = meta.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(KotoriShapes.browseCover)
                                    .background(KotoriColors.glassBg),
                            )
                            Text(
                                text = meta.name,
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

/** Episodes + streams of one meta; taps resolve streams and open the player. */
class StremioDetailScreen(
    private val manifestUrl: String,
    private val type: String,
    private val metaId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var streamsFor by remember { mutableStateOf<String?>(null) }
        var streams by remember { mutableStateOf(emptyList<StremioClient.Stream>()) }
        var loadingStreams by remember { mutableStateOf(false) }

        val meta by produceState<StremioClient.Meta?>(initialValue = null) {
            value = runCatching { StremioClient.fetchMeta(manifestUrl, type, metaId) }.getOrNull()
        }

        val videos = meta?.videos.orEmpty().ifEmpty {
            meta?.let { listOf(StremioClient.MetaVideo(id = it.id, title = it.name)) }.orEmpty()
        }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = meta?.name ?: "Đang tải…",
                    subtitle = "${videos.size} tập · Stremio",
                    onNavigateUp = navigator::pop,
                )
            },
        ) { _ ->
            LazyColumn {
                items(videos, key = { it.id }) { video ->
                    val label = video.title ?: video.name
                        ?: listOfNotNull(video.season?.let { "S$it" }, video.episode?.let { "E$it" })
                            .joinToString(" ")
                            .ifEmpty { video.id }
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 4.5.dp)
                            .glass(shape = KotoriShapes.row)
                            .clickable {
                                loadingStreams = true
                                streamsFor = label
                                scope.launch {
                                    streams = runCatching {
                                        StremioClient.fetchStreams(manifestUrl, type, video.id)
                                    }.getOrDefault(emptyList())
                                    loadingStreams = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = label,
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

        if (streamsFor != null) {
            AlertDialog(
                onDismissRequest = { streamsFor = null },
                title = { Text(streamsFor.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    when {
                        loadingStreams -> Text("Đang lấy stream…")
                        streams.isEmpty() -> Text(
                            "Không có stream phát trực tiếp (addon chỉ trả torrent hoặc trống).",
                        )
                        else -> LazyColumn(modifier = Modifier.height(320.dp)) {
                            items(streams) { stream ->
                                Text(
                                    text = listOfNotNull(stream.name, stream.title ?: stream.description)
                                        .joinToString(" · ")
                                        .ifEmpty { stream.url.orEmpty() },
                                    fontFamily = BeVietnamProFamily,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val url = stream.url ?: return@clickable
                                            context.startActivity(
                                                AnimePlayerActivity.newIntent(
                                                    context = context,
                                                    url = url,
                                                    title = meta?.name ?: "Stremio",
                                                    episodeLabel = streamsFor.orEmpty(),
                                                    sourceLabel = "Stremio",
                                                ),
                                            )
                                            streamsFor = null
                                        }
                                        .padding(vertical = 10.dp),
                                    maxLines = 3,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { streamsFor = null }) { Text("Đóng") }
                },
            )
        }
    }
}
