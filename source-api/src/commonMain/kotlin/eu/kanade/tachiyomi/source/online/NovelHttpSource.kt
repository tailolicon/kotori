package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/**
 * Base class for novel sources served over HTTP.
 *
 * [HttpSource] is inherited for its client, headers, id generation and URL helpers; the manga
 * vocabulary that comes with it ([SManga], "manga" in method names) is the price of reusing the
 * whole stack, and a novel simply *is* the [SManga] here.
 *
 * Subclasses implement the two suspend hooks below plus [NovelSource.getChapterText]; the fetch/
 * parse Observable API of [HttpSource] is legacy and stays unused.
 */
abstract class NovelHttpSource : HttpSource(), NovelSource {

    /** Full metadata for a novel: description, author, status, genres, cover. */
    abstract suspend fun getNovelDetails(novel: SManga): SManga

    /** Every chapter of a novel, newest first, matching how the manga stack expects chapters. */
    abstract suspend fun getChapterList(novel: SManga): List<SChapter>

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(
        manga = if (fetchDetails) getNovelDetails(manga) else manga,
        chapters = if (fetchChapters) getChapterList(manga) else chapters,
    )
}
