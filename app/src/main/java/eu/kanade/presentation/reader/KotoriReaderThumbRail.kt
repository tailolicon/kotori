package eu.kanade.presentation.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.kotori.KotoriColors
import eu.kanade.presentation.theme.kotori.KotoriTheme
import eu.kanade.presentation.theme.kotori.UnboundedFamily
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import tachiyomi.core.common.util.lang.withIOContext

private const val THUMB_TARGET_WIDTH = 172

/**
 * T4 · the 126 dp page rail: 86×120 plates of the chapter's pages with the spread being
 * read outlined in the mode accent. Tapping a plate jumps to that page.
 */
@Composable
fun KotoriReaderThumbRail(
    pages: List<ReaderPage>,
    currentIndices: Set<Int>,
    onSelect: (ReaderPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = KotoriTheme.accent
    val listState = rememberLazyListState()
    val firstCurrent = currentIndices.minOrNull()
    LaunchedEffect(firstCurrent, pages.size) {
        val index = pages.indexOfFirst { it.index == firstCurrent }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(
        modifier = modifier
            .width(126.dp)
            .fillMaxHeight()
            .background(Color(0xE6100C16))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "TRANG",
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            letterSpacing = 0.16.em,
            color = accent.light,
        )
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pages, key = { it.index }) { page ->
                KotoriPagePlate(
                    page = page,
                    active = page.index in currentIndices,
                    accent = accent.end,
                    onClick = { onSelect(page) },
                )
            }
        }
    }
}

@Composable
private fun KotoriPagePlate(
    page: ReaderPage,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    // Decoded straight off the page's own stream at a thumbnail sample size — the rail must
    // not pull full-size bitmaps into memory just to draw a 86dp plate.
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, page, page.stream) {
        val provider = page.stream ?: return@produceState
        value = withIOContext {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                provider().use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = generateSequence(1) { it * 2 }
                        .first { bounds.outWidth / it <= THUMB_TARGET_WIDTH }
                }
                provider().use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .width(86.dp)
            .height(120.dp)
            .clip(shape)
            .background(Color(0xFF1B1629))
            .border(2.dp, if (active) accent else Color.Transparent, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = "${page.number}",
            fontFamily = UnboundedFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = if (active) Color.White else KotoriColors.textSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 5.dp, bottom = 4.dp),
        )
    }
}
