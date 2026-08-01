package eu.kanade.tachiyomi.ui.reader.model

/**
 * Two pages shown side by side as one pager item (T4).
 *
 * The pager is one-item-per-screen, so a spread has to *be* an item rather than a way of
 * drawing one; [first] and [second] are in reading order, and the holder is what decides
 * which side each lands on for right-to-left.
 */
data class PageSpread(
    val first: ReaderPage,
    val second: ReaderPage,
) {
    /** The page the reader is considered to be on — progress and bookmarks follow this one. */
    val anchor: ReaderPage get() = first

    operator fun contains(page: ReaderPage): Boolean = page == first || page == second
}
