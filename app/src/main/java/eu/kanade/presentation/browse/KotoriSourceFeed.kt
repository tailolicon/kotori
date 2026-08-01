package eu.kanade.presentation.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.kotori.BeVietnamProFamily
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.LocalKotoriAccent
import eu.kanade.presentation.theme.kotori.UnboundedFamily

/** One entry as the feed draws it, so manga and anime can share the whole screen. */
data class KotoriFeedItem(
    val key: String,
    val title: String,
    val cover: Any?,
    val statusLabel: String?,
    val inLibrary: Boolean,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
)

/** A titled horizontal shelf. */
data class KotoriFeedShelf(
    val label: String,
    val sub: String,
    val items: List<KotoriFeedItem>,
)

private val HeroShape = RoundedCornerShape(
    topStart = 26.dp,
    topEnd = 26.dp,
    bottomEnd = 26.dp,
    bottomStart = 9.dp,
)
private val CardShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomEnd = 16.dp,
    bottomStart = 5.dp,
)
private val TopCardShape = RoundedCornerShape(
    topStart = 15.dp,
    topEnd = 15.dp,
    bottomEnd = 15.dp,
    bottomStart = 5.dp,
)

/**
 * Screen 18 · what a source opens on before anything is filtered.
 *
 * A hero, the source's genres as chips, a ranked row and one shelf per status, instead of the
 * flat grid. The grid is still what a search or a filter returns — this is the browsing state,
 * and the two are exclusive in the design.
 */
@Composable
fun KotoriSourceFeed(
    hero: KotoriFeedItem?,
    top: List<KotoriFeedItem>,
    shelves: List<KotoriFeedShelf>,
    genres: List<String>,
    activeGenre: String?,
    onSelectGenre: (String?) -> Unit,
    onPlayHero: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (hero != null) {
            item("hero") { KotoriFeedHero(item = hero, onPlay = onPlayHero) }
        }
        if (genres.isNotEmpty()) {
            item("genres") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item("all") {
                        KotoriGenreChip("Tất cả", activeGenre == null) { onSelectGenre(null) }
                    }
                    items(genres, key = { it }) { genre ->
                        KotoriGenreChip(genre, genre == activeGenre) { onSelectGenre(genre) }
                    }
                }
            }
        }
        if (top.isNotEmpty()) {
            item("top") {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    KotoriShelfHeader(label = "TOP 10 TRONG NGÀY", sub = "")
                    LazyRow(
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(top, key = { _, it -> "top-${it.key}" }) { index, item ->
                            KotoriRankedCard(rank = index + 1, item = item)
                        }
                    }
                }
            }
        }
        items(shelves, key = { it.label }) { shelf ->
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                KotoriShelfHeader(label = shelf.label, sub = shelf.sub)
                LazyRow(
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    items(shelf.items, key = { "${shelf.label}-${it.key}" }) { item ->
                        KotoriShelfCard(item = item)
                    }
                }
            }
        }
        item("tail") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun KotoriFeedHero(item: KotoriFeedItem, onPlay: () -> Unit) {
    val accent = LocalKotoriAccent.current
    Box(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .height(196.dp)
            .clip(HeroShape)
            .border(1.dp, Color(0x1FFFFFFF), HeroShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = item.onClick,
                onLongClick = item.onLongClick,
            ),
    ) {
        KotoriCoverImage(data = item.cover, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.26f to Color.Transparent,
                        1f to Color(0xE60B0812),
                    ),
                ),
        )
        Text(
            text = "NỔI BẬT HÔM NAY",
            fontFamily = BeVietnamProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            letterSpacing = 0.16.em,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x800B0812))
                .border(1.dp, Color(0x3DFFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontFamily = UnboundedFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    lineHeight = 24.sp,
                    color = KotoriColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.statusLabel != null) {
                    Text(
                        text = item.statusLabel,
                        fontFamily = BeVietnamProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.5.sp,
                        color = Color(0xFFE9E4F7),
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0x29FFFFFF))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accent.ctaGradient)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = accent.onAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun KotoriShelfHeader(label: String, sub: String) {
    val accent = LocalKotoriAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = label,
            fontFamily = UnboundedFamily,
            fontSize = 11.sp,
            letterSpacing = 0.16.em,
            color = accent.light,
        )
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                fontFamily = BeVietnamProFamily,
                fontSize = 10.5.sp,
                color = KotoriColors.textFaint,
            )
        }
    }
}

@Composable
private fun KotoriGenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalKotoriAccent.current
    Text(
        text = label,
        fontFamily = BeVietnamProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        color = if (selected) accent.onAccent else KotoriColors.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .then(
                if (selected) {
                    Modifier.background(accent.gradient)
                } else {
                    Modifier
                        .background(Color(0x0FFFFFFF))
                        .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(15.dp))
                },
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun KotoriRankedCard(rank: Int, item: KotoriFeedItem) {
    Row(verticalAlignment = Alignment.Bottom) {
        // Outlined numeral, tucked under the cover's left edge as in the mock.
        Text(
            text = rank.toString(),
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 52.sp,
            color = Color(0x52FFFFFF),
            modifier = Modifier.padding(end = 0.dp),
        )
        KotoriFeedCard(
            item = item,
            width = 96.dp,
            height = 134.dp,
            shape = TopCardShape,
            titleSize = 10.sp,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun KotoriShelfCard(item: KotoriFeedItem) {
    KotoriFeedCard(
        item = item,
        width = 112.dp,
        height = 158.dp,
        shape = CardShape,
        titleSize = 10.5.sp,
    )
}

@Composable
private fun KotoriFeedCard(
    item: KotoriFeedItem,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    shape: RoundedCornerShape,
    titleSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(shape)
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = item.onClick,
                onLongClick = item.onLongClick,
            ),
    ) {
        KotoriCoverImage(data = item.cover, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0812))),
                )
                .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 7.dp),
        ) {
            Text(
                text = item.title,
                fontFamily = BeVietnamProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = titleSize,
                lineHeight = titleSize * 1.25f,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.statusLabel != null) {
                Text(
                    text = item.statusLabel,
                    fontFamily = BeVietnamProFamily,
                    fontSize = 9.sp,
                    color = Color(0xA8FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (item.inLibrary) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0x9E0B0812)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = KotoriColors.success,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** The cover art itself; the mock's gradient stand-ins are runtime art loaded through Coil. */
@Composable
private fun KotoriCoverImage(data: Any?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(KotoriColors.glassBgElevated)) {
        if (data != null) {
            AsyncImage(
                model = data,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
