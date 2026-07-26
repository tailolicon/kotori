package eu.kanade.presentation.browse.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Slides a browse screen's header out of the way while the reader scrolls down through the grid,
 * and brings it back the moment they scroll up.
 *
 * A browsing screen is mostly covers, and on a phone the title bar plus the listing chips eat a
 * meaningful slice of them. Material's own collapsing behaviour only moves the app bar, leaving the
 * chip row pinned; this drives the whole header instead, which is what makes the space usable.
 *
 * The header's own height is the travel limit, measured rather than assumed, since the chip row is
 * a different height on each of the three browse screens.
 */
class BrowseHeaderScrollState {

    private var height by mutableIntStateOf(0)

    /** How far the header is currently pushed up: 0 when fully shown, -height when hidden. */
    var offset by mutableFloatStateOf(0f)
        private set

    /**
     * Measures the header and applies the current offset to it.
     *
     * Both belong together: the offset is meaningless without the height it is clamped to.
     */
    fun headerModifier(): Modifier = Modifier
        .onSizeChanged { size ->
            height = size.height
            offset = offset.coerceIn(-height.toFloat(), 0f)
        }
        .offset { IntOffset(0, offset.roundToInt()) }

    /**
     * Consumes nothing — the grid keeps every pixel of the gesture.
     *
     * The header rides along with the scroll rather than competing with it, so a flick moves the
     * list exactly as far as it would have without a collapsing header.
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (height > 0) offset = (offset + available.y).coerceIn(-height.toFloat(), 0f)
            return Offset.Zero
        }
    }
}

@Composable
fun rememberBrowseHeaderScrollState(): BrowseHeaderScrollState = remember { BrowseHeaderScrollState() }
