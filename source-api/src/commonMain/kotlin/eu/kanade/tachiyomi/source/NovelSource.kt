package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A source whose chapters are prose rather than images.
 *
 * Novels reuse the manga stack wholesale — the same catalogue, database, download queue, history
 * and extension loader — because a novel chapter maps onto [SChapter] exactly as a manga chapter
 * does. The single thing that differs is what a chapter *contains*, so that is the only thing this
 * interface adds. The app branches on `source is NovelSource` to route reading and downloading to
 * text instead of pages.
 *
 * [getPageList] is meaningless here and must not be called; implementations inherit a throwing
 * default so a mis-routed caller fails loudly instead of silently rendering an empty reader.
 */
interface NovelSource : CatalogueSource {

    /**
     * Full text of a chapter.
     *
     * Returns plain text, not markup: paragraphs separated by blank lines. Sources are responsible
     * for stripping the site's HTML, ads and navigation chrome, since the reader renders the string
     * verbatim.
     */
    suspend fun getChapterText(chapter: SChapter): String

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("Novel chapters are text; use getChapterText")
}
