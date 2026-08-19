package mihon.feature.translation.render

import kotlin.math.max

/**
 * How far to look for a speech balloon around recognised lettering.
 *
 * The bubble model is trained on webtoon balloons and routinely returns nothing on hand-drawn
 * manga. Growing the text box *isotropically* then fails in the other direction: a tall Japanese
 * column is mostly height, so a 90% grow escapes into the panel above and below while still
 * missing the balloon's sides. Growth has to follow the short axis — sideways for a column,
 * up-and-down for a row — which is how a letterer finds the paper the words sit on.
 *
 * Pure geometry: the flood itself lives in [BubbleFill].
 */
internal object PaperBalloonFinder {

    data class Area(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun searchArea(
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int,
        pageWidth: Int,
        pageHeight: Int,
        vertical: Boolean,
    ): Area {
        val width = (textRight - textLeft).coerceAtLeast(1)
        val height = (textBottom - textTop).coerceAtLeast(1)
        val growX: Int
        val growY: Int
        if (vertical) {
            growX = max((width * VERTICAL_GROW_X).toInt(), MIN_SIDE_GROW)
            growY = max((height * VERTICAL_GROW_Y).toInt(), MIN_LONG_GROW)
        } else {
            growX = max((width * HORIZONTAL_GROW_X).toInt(), MIN_LONG_GROW)
            growY = max((height * HORIZONTAL_GROW_Y).toInt(), MIN_SIDE_GROW)
        }
        return Area(
            left = (textLeft - growX).coerceAtLeast(0),
            top = (textTop - growY).coerceAtLeast(0),
            right = (textRight + growX).coerceAtMost(pageWidth),
            bottom = (textBottom + growY).coerceAtMost(pageHeight),
        )
    }

    fun isVertical(width: Int, height: Int): Boolean =
        height > width * VERTICAL_ASPECT

    const val VERTICAL_ASPECT = 1.5f

    /** Sideways reach around a column, as a multiple of the column's own width. */
    const val VERTICAL_GROW_X = 2.2f

    /** Vertical reach around a column — small, so the flood stays inside one panel. */
    const val VERTICAL_GROW_Y = 0.28f

    const val HORIZONTAL_GROW_X = 0.40f
    const val HORIZONTAL_GROW_Y = 1.6f

    const val MIN_SIDE_GROW = 24
    const val MIN_LONG_GROW = 12
}
