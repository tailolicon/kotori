package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import mihon.feature.translation.TranslationManager
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

/**
 * Wraps another [PageLoader] and hands the viewer translated artwork instead of the original.
 *
 * The interception point is [ReaderPage.stream] rather than the page's status. That choice matters:
 * substituting on status would race the viewer, which starts decoding the instant a page reports
 * ready, and losing that race shows the untranslated page for a frame. Overriding the stream getter
 * means the viewer physically cannot obtain untranslated bytes.
 *
 * Translation is therefore synchronous from the decoder's point of view. In practice it returns
 * immediately, because [TranslationManager] has already translated the chapter in the background by
 * the time a page is displayed; the blocking path only runs if the reader outruns the prefetch.
 */
internal class TranslatingPageLoader(
    private val delegate: PageLoader,
    private val mangaId: Long,
    private val chapterId: Long,
    private val manager: TranslationManager,
) : PageLoader() {

    override var isLocal: Boolean = delegate.isLocal

    override suspend fun getPages(): List<ReaderPage> = delegate.getPages().map { page ->
        TranslatedReaderPage(page, mangaId, chapterId, manager)
    }

    override suspend fun loadPage(page: ReaderPage) = delegate.loadPage(page)

    override fun retryPage(page: ReaderPage) = delegate.retryPage(page)

    override fun recycle() {
        super.recycle()
        delegate.recycle()
    }
}

/**
 * A page whose bytes are replaced by their translated counterpart on read.
 *
 * The underlying loader keeps writing to [stream] as usual; that value is captured as the original
 * and only ever used as the translation input, or as the fallback when a page has no dialogue.
 */
internal class TranslatedReaderPage(
    source: ReaderPage,
    private val mangaId: Long,
    private val chapterId: Long,
    private val manager: TranslationManager,
) : ReaderPage(source.index, source.url, source.imageUrl) {

    private var original: (() -> InputStream)? = source.stream

    init {
        status = source.status
    }

    /**
     * The untranslated bytes, for callers that want to translate a run of pages together rather than
     * one at a time. Reading [stream] would translate this page on its own and defeat the point.
     */
    fun originalStream(): (() -> InputStream)? = original

    override var stream: (() -> InputStream)?
        get() {
            val source = original ?: return null
            return { translatedOrOriginal(source) }
        }
        set(value) {
            original = value
        }

    private fun translatedOrOriginal(source: () -> InputStream): InputStream {
        val translated = runCatching {
            runBlocking { manager.translatedPage(mangaId, chapterId, index, source) }
        }.onFailure {
            logcat { "Translation lookup failed for page $index: ${it.message}" }
        }.getOrNull()

        return translated?.inputStream() ?: source()
    }
}
