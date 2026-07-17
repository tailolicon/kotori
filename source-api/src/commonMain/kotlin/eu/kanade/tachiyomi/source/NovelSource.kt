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
     *
     * Not called when [chapterWebUrl] returns a URL for the chapter.
     */
    suspend fun getChapterText(chapter: SChapter): String

    /**
     * URL the chapter should be rendered from by the site itself, or null to use [getChapterText].
     *
     * Some sites deliver chapter text only to their own page scripts — docln.net, for instance,
     * ships it obfuscated and has the page decode it for display. Rather than reimplementing a
     * scheme a site put up precisely to stop third parties lifting its text, such a source points
     * here and the reader embeds the real page, which is how the site means the chapter to be read.
     *
     * The trade-off is real and deliberate: text served this way cannot be extracted, so it cannot
     * be downloaded for offline reading or restyled by the reader's own typography settings.
     */
    fun chapterWebUrl(chapter: SChapter): String? = null

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("Novel chapters are text; use getChapterText")
}
