package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    /**
     * Open. Subclasses interpose on page bytes between the loader that fetched them and the viewer
     * that decodes them — see `TranslatedReaderPage`, which substitutes translated artwork.
     */
    open var stream: (() -> InputStream)? = stream

    open lateinit var chapter: ReaderChapter
}
