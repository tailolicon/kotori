package mihon.feature.novelreader

import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Loads a novel chapter's text and records reading progress.
 *
 * Progress is stored as a percentage in the chapter's `last_page_read` column — the same column the
 * page reader uses for its page index. Prose has no pages to count, and a percentage survives font
 * and screen-size changes that would invalidate any absolute offset.
 */
class NovelReaderViewModel(
    private val mangaId: Long,
    private val initialChapterId: Long?,
    private val scope: CoroutineScope,
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
) {

    data class State(
        val manga: Manga? = null,
        val chapter: Chapter? = null,
        val chapters: List<Chapter> = emptyList(),
        val content: String = "",
        val isLoading: Boolean = true,
        /** Adjacent chapter is loading while the current chapter remains visible. */
        val isChangingChapter: Boolean = false,
        val error: String? = null,
        /** Where to restore the reader to, 0..100. */
        val startPercent: Int = 0,
        /** Set when the source wants its own page to render the chapter; [content] is then unused. */
        val webUrl: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** Latest scroll position reported by the UI, 0..100. Written back on pause/chapter change. */
    private var currentPercent: Int = 0
    private var readStartedAt: Long = System.currentTimeMillis()

    init {
        scope.launchIO { load(initialChapterId) }
    }

    private suspend fun load(chapterId: Long?) {
        val keepCurrentChapter = _state.value.chapter != null
        _state.update {
            it.copy(
                isLoading = !keepCurrentChapter,
                isChangingChapter = keepCurrentChapter,
                error = null,
            )
        }

        fun fail(message: String) {
            _state.update {
                if (keepCurrentChapter) {
                    it.copy(isChangingChapter = false)
                } else {
                    it.copy(isLoading = false, error = message)
                }
            }
        }

        val manga = getManga.await(mangaId)
        if (manga == null) {
            fail("Không tìm thấy truyện.")
            return
        }
        val chapters = getChaptersByMangaId.await(mangaId)
        val chapter = chapters.firstOrNull { it.id == chapterId }
            ?: chapters.lastOrNull()
        if (chapter == null) {
            fail("Truyện chưa có chương nào.")
            return
        }

        val source = sourceManager.get(manga.source) as? NovelSource
        if (source == null) {
            fail("Nguồn truyện không khả dụng.")
            return
        }

        // A source may hand the chapter back as a URL its own page must render; there is no text
        // to fetch in that case.
        val webUrl = source.chapterWebUrl(chapter.toSChapter())
        // A downloaded chapter reads from disk, so it works offline and doesn't re-hit the source.
        val downloaded = downloadProvider.getSavedNovelText(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        )
        val text = when {
            webUrl != null -> ""
            downloaded != null -> downloaded
            else -> try {
                source.getChapterText(chapter.toSChapter())
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter ${chapter.url}" }
                fail("Không tải được nội dung: ${e.message}")
                return
            }
        }

        currentPercent = chapter.lastPageRead.toInt().coerceIn(0, 100)
        readStartedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                manga = manga,
                chapter = chapter,
                chapters = chapters,
                content = text,
                isLoading = false,
                isChangingChapter = false,
                startPercent = currentPercent,
                webUrl = webUrl,
            )
        }
    }

    /** Called as the reader scrolls; cheap, so it only updates memory. */
    fun onProgress(percent: Int) {
        currentPercent = percent.coerceIn(0, 100)
    }

    fun loadChapter(chapterId: Long) {
        val state = _state.value
        if (state.isChangingChapter || state.chapter?.id == chapterId) return
        _state.update { it.copy(isChangingChapter = true, error = null) }
        flushProgress()
        scope.launchIO { load(chapterId) }
    }

    fun toggleBookmark() {
        val chapter = _state.value.chapter ?: return
        val bookmarked = !chapter.bookmark
        _state.update { state ->
            state.copy(
                chapter = state.chapter?.copy(bookmark = bookmarked),
                chapters = state.chapters.map {
                    if (it.id == chapter.id) it.copy(bookmark = bookmarked) else it
                },
            )
        }
        scope.launch {
            try {
                updateChapter.await(ChapterUpdate(id = chapter.id, bookmark = bookmarked))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to update novel chapter bookmark" }
                _state.update { state ->
                    if (state.chapter?.id == chapter.id && state.chapter.bookmark == bookmarked) {
                        state.copy(
                            chapter = state.chapter.copy(bookmark = chapter.bookmark),
                            chapters = state.chapters.map {
                                if (it.id == chapter.id) it.copy(bookmark = chapter.bookmark) else it
                            },
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    /** Persists the current position. Safe to call repeatedly. */
    fun flushProgress() {
        val chapter = _state.value.chapter ?: return
        val percent = currentPercent
        val sessionDuration = System.currentTimeMillis() - readStartedAt
        readStartedAt = System.currentTimeMillis()

        scope.launch {
            try {
                updateChapter.await(
                    ChapterUpdate(
                        id = chapter.id,
                        lastPageRead = percent.toLong(),
                        // Anything past the tail is the chapter finished; the reader never scrolls
                        // to a true 100 on a long chapter, so leave a margin.
                        read = if (percent >= READ_THRESHOLD_PERCENT) true else chapter.read,
                    ),
                )
                upsertHistory.await(HistoryUpdate(chapter.id, Date(), sessionDuration))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to save novel progress" }
            }
        }
    }

    private fun Chapter.toSChapter(): SChapter = SChapter.create().also {
        it.url = url
        it.name = name
        it.scanlator = scanlator
        it.chapter_number = chapterNumber.toFloat()
        it.date_upload = dateUpload
    }

    companion object {
        private const val READ_THRESHOLD_PERCENT = 98
    }
}
