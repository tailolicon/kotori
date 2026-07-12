package mihon.feature.mediasource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import mihon.feature.localmedia.LocalMediaColumn
import mihon.feature.localmedia.LocalMediaDetailScreen

/**
 * Browse tab content for ANIME/NOVEL modes (design 07 source rows + screen 17
 * type tags): lists media sources of the active type only — manga extension
 * repos never leak into these modes.
 */
@Composable
fun MediaBrowseContent(mediaType: MediaType) {
    val navigator = LocalNavigator.currentOrThrow
    val accent = mediaType.accent
    val sources = remember(mediaType) { OnlineMediaSourceRegistry.sourcesFor(mediaType) }
    val typeLabel = if (mediaType == MediaType.ANIME) "ANIME" else "NOVEL"

    KotoriScreenScaffold(
        header = {
            KotoriHeader(title = "Duyệt")
        },
    ) { _ ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            if (mediaType == MediaType.ANIME) {
                // Aniyomi extension repo + Stremio hub live above built-in sources.
                AnimeExtensionEntryRows(accent = accent)
            }
            KotoriSectionLabel(
                text = "NGUỒN · $typeLabel",
                accent = accent,
                modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
            )
            if (sources.isEmpty()) {
                KotoriEmptyState(
                    title = "Chưa có nguồn $typeLabel",
                    hint = "Tiện ích $typeLabel sẽ xuất hiện ở đây sau khi cài",
                )
            } else {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 4.5.dp)
                            .glass(shape = KotoriShapes.row)
                            .clickable {
                                navigator.push(OnlineSourceCatalogScreen(source.id, mediaType))
                            }
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
                                text = source.name.take(1).uppercase(),
                                fontFamily = UnboundedFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = accent.onAccent,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.name,
                                fontFamily = BeVietnamProFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = KotoriColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${source.lang.uppercase()} · $typeLabel",
                                fontFamily = BeVietnamProFamily,
                                fontSize = 10.5.sp,
                                color = KotoriColors.textMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "Tiện ích $typeLabel bên ngoài đăng ký qua giao thức OnlineMediaSource.",
                    fontFamily = BeVietnamProFamily,
                    fontSize = 10.5.sp,
                    color = KotoriColors.textFaint,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Entry rows into the Aniyomi anime extension repo and the Stremio hub. */
@Composable
private fun AnimeExtensionEntryRows(accent: eu.kanade.presentation.theme.kotori.KotoriAccent) {
    val navigator = LocalNavigator.currentOrThrow
    KotoriSectionLabel(
        text = "NGUỒN CHẤT LƯỢNG CAO",
        accent = accent,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
    )
    EntryRow(
        title = "Tiện ích Anime (Aniyomi)",
        subtitle = "Kho ~250 nguồn · cập nhật cộng đồng",
        letter = "A",
        accent = accent,
        onClick = { navigator.push(mihon.feature.animeextension.AnimeExtensionScreen()) },
    )
    EntryRow(
        title = "Stremio",
        subtitle = "Nguồn stream chất lượng cao qua addon",
        letter = "S",
        accent = accent,
        onClick = { navigator.push(mihon.feature.stremio.StremioHubScreen()) },
    )
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    letter: String,
    accent: eu.kanade.presentation.theme.kotori.KotoriAccent,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.5.dp)
            .glass(shape = KotoriShapes.row)
            .clickable(onClick = onClick)
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
                text = letter,
                fontFamily = UnboundedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = accent.onAccent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = KotoriColors.textPrimary,
            )
            Text(
                text = subtitle,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Catalog of one online media source. */
class OnlineSourceCatalogScreen(
    private val sourceId: Long,
    private val mediaType: MediaType,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = remember(sourceId) {
            OnlineMediaSourceRegistry.sourcesFor(mediaType).find { it.id == sourceId }
        }
        val entries by produceState(initialValue = emptyList<mihon.feature.localmedia.LocalMediaEntry>(), source) {
            value = source?.let { s ->
                runCatching { s.fetchCatalog().map { it.copy(sourceName = s.name) } }
                    .getOrDefault(emptyList())
            }.orEmpty()
        }

        KotoriScreenScaffold(
            header = {
                KotoriHeader(
                    title = source?.name ?: "Nguồn",
                    subtitle = "${entries.size} bộ · trực tuyến",
                    onNavigateUp = navigator::pop,
                )
            },
        ) { _ ->
            if (entries.isEmpty()) {
                KotoriEmptyState(title = "Đang tải hoặc nguồn trống…")
            } else {
                LocalMediaColumn(
                    entries = entries,
                    mediaType = mediaType,
                    onClickEntry = { navigator.push(LocalMediaDetailScreen(it)) },
                )
            }
        }
    }
}
