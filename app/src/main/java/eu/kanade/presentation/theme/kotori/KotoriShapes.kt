package eu.kanade.presentation.theme.kotori

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Kotori signature shapes: three large corners + one small "clipped" corner.
 * Radii per design_handoff README "Radii" table.
 */
object KotoriShapes {
    /** List rows / glass cards: 18 18 18 6 */
    val row = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomEnd = 18.dp,
        bottomStart = 6.dp,
    )

    /** Large hero cards: 26 26 26 8 */
    val hero = RoundedCornerShape(
        topStart = 26.dp,
        topEnd = 26.dp,
        bottomEnd = 26.dp,
        bottomStart = 8.dp,
    )

    /** Thumbnails inside rows (small): 12 12 12 4 */
    val thumbSmall = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomEnd = 12.dp,
        bottomStart = 4.dp,
    )

    /** Thumbnails inside hero rows: 16 16 16 4 */
    val thumb = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 4.dp,
    )

    /** Primary CTA buttons: 20 8 20 20 */
    val cta = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 8.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp,
    )

    /** Active segment in switchers: 18 6 18 18 */
    val segmentActive = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 6.dp,
        bottomEnd = 18.dp,
        bottomStart = 18.dp,
    )

    /** Segment container / inactive: uniform 18 */
    val segment = RoundedCornerShape(18.dp)

    /** Chips / small buttons: uniform 13–16 */
    val chip = RoundedCornerShape(14.dp)

    /** Search fields and larger pills */
    val pill = RoundedCornerShape(16.dp)

    /** Bottom nav container */
    val nav = RoundedCornerShape(28.dp)

    /** Bottom sheets: 28 28 0 0 */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Source/extension monogram tiles: 14 14 14 4 */
    val monogram = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomEnd = 14.dp,
        bottomStart = 4.dp,
    )

    /** Browse grid covers: 16 16 16 5 */
    val browseCover = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 5.dp,
    )

    /** Stat cards: 20 20 20 7 */
    val statCard = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = 20.dp,
        bottomStart = 7.dp,
    )

    /**
     * Library cover tiles: radius 24 with one 8 corner whose position rotates
     * per tile index: [bottom-left, bottom-right, top-left, top-right].
     */
    fun libraryTile(index: Int): RoundedCornerShape {
        val big = 24.dp
        val small = 8.dp
        return when (index % 4) {
            0 -> RoundedCornerShape(big, big, big, small) // clipped bottom-left(start)
            1 -> RoundedCornerShape(big, big, small, big) // clipped bottom-right(end)
            2 -> RoundedCornerShape(small, big, big, big) // clipped top-left(start)
            else -> RoundedCornerShape(big, small, big, big) // clipped top-right(end)
        }
    }
}
