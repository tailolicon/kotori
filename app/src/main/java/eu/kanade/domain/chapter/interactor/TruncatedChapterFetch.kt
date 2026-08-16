package eu.kanade.domain.chapter.interactor

/**
 * Decides whether a chapter-list fetch that would delete known chapters is trustworthy.
 *
 * Madara-style sources page the chapter list. A silent failure on a later page arrives as a
 * short list: the newest chapters (page 1) are present — often including one that is genuinely
 * new — and a contiguous block of older ones is simply missing. Deleting on that evidence made
 * whole ranges vanish until the next successful refresh, then vanish again on the next partial
 * one.
 *
 * A real takedown or a scanlator switch keeps the list roughly the same length (chapters are
 * replaced, not dropped). A truncated page is shorter than what we already hold.
 */
object TruncatedChapterFetch {

    const val SUSPICIOUS_REMOVED_COUNT = 5

    fun shouldKeepExisting(
        dbCount: Int,
        sourceCount: Int,
        removedCount: Int,
        manualFetch: Boolean,
    ): Boolean {
        if (manualFetch) return false
        if (removedCount < SUSPICIOUS_REMOVED_COUNT) return false
        return sourceCount < dbCount
    }
}
