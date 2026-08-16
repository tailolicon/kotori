package eu.kanade.tachiyomi.source.novel

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Shared rules for turning a site's chapter markup into the flat String the reader renders.
 *
 * `NovelSource.getChapterText` returns one String, so a source with pictures has to encode them into
 * it. Every source that does so must agree on the encoding, and every source scraping lazy-loaded
 * markup hits the same attribute soup, so both live here rather than being copied per source.
 */
object NovelChapterHtml {

    /**
     * Marks a line of chapter text as an illustration rather than prose: the sentinel is immediately
     * followed by the image's absolute URL. This private-use code point cannot collide with real
     * prose, and the reader renders any line starting with it as an image.
     */
    const val IMAGE_SENTINEL: String = ""

    /**
     * Where a lazy-loading page keeps the real image. The lazy attributes are read before `src`
     * because on such a page `src` holds a placeholder that would otherwise win.
     */
    private val IMAGE_URL_ATTRS = listOf(
        "data-src",
        "data-original",
        "data-lazy-src",
        "data-srcset",
        "data-bg",
        "data-echo",
        "data-image",
        "src",
        "srcset",
    )

    /** Placeholder sources a lazy-loading page parks in `src` until the real image arrives. */
    private val PLACEHOLDER_MARKERS = listOf("data:image", "blank.", "placeholder", "loading.", "spacer.")

    /** A sentinel-prefixed line for [element]'s picture, or null when it has no usable source. */
    fun imageBlock(element: Element): String? = imageUrl(element)?.let { IMAGE_SENTINEL + it }

    /**
     * First trusted source among [IMAGE_URL_ATTRS]; blank, unparseable, placeholder and untrusted
     * values are skipped, so an attribute holding a non-image destination simply yields no picture.
     *
     * `srcset` holds a comma-separated candidate list rather than one URL, so each attribute is
     * expanded into its candidates before parsing — otherwise the whole list fails to parse and a
     * page that lazy-loads only through `srcset` would lose every illustration.
     */
    fun imageUrl(element: Element): String? {
        val base = element.baseUri().toHttpUrlOrNull()
        return IMAGE_URL_ATTRS
            .asSequence()
            .flatMap { element.attr(it).toUrlCandidates() }
            .filterNot { candidate -> PLACEHOLDER_MARKERS.any { candidate.contains(it, true) } }
            .mapNotNull { base?.resolve(it) ?: it.toHttpUrlOrNull() }
            .firstOrNull(NovelImagePolicy::isTrusted)
            ?.toString()
    }

    /**
     * Walks [body] in document order, emitting prose paragraphs and illustration lines interleaved
     * as they appear.
     *
     * Jsoup lists a `<p>` before its own children, so text keeps its place around images. When the
     * markup uses `<div>`/`<br>` instead of paragraphs there is nothing to walk, so the prose comes
     * from the body as a whole and the pictures are appended — which beats dropping either half.
     */
    fun toBlocks(body: Element): List<String> {
        val cleanText: (String) -> List<String> = { text -> textBlocks(text) }
        if (body.selectFirst("p") == null) {
            return cleanText(body.wholeText()) + body.select("img").mapNotNull(::imageBlock)
        }
        return body.select("p, img").flatMap { element ->
            if (element.tagName() == "img") {
                listOfNotNull(imageBlock(element))
            } else {
                cleanText(element.wholeText())
            }
        }
    }

    /** Splits a block's text into reader paragraphs, dropping any sentinel so it can never show. */
    fun textBlocks(text: String): List<String> = text.lineSequence()
        .map { it.replace(IMAGE_SENTINEL, "").replace(' ', ' ').trim() }
        .filter(String::isNotEmpty)
        .toList()

    /**
     * Removes the markup a reader never wants and normalises line breaks.
     *
     * `<br>` has to become a real newline before any text is read: Jsoup's `wholeText` treats it as
     * an element and not as whitespace, so a paragraph written with `<br>` between its lines would
     * otherwise come out as one run-on block — which the reader would render as a wall of text and
     * the sentence splitter would then have to guess its way through.
     */
    fun stripHiddenContent(body: Element) {
        body.select("script, style, noscript, [hidden], .none, .hidden").remove()
        body.getAllElements()
            .filter { it.attr("style").replace(" ", "").contains("display:none", ignoreCase = true) }
            .forEach(Element::remove)
        body.select("br").forEach { it.replaceWith(TextNode("\n")) }
    }

    /**
     * Splits a `srcset`-style value into its candidate URLs, widest first; a plain value yields just
     * itself.
     *
     * Widest first because an illustration is displayed at full column width, and a `srcset`'s first
     * entry is conventionally its *smallest* — taking it verbatim would hand the reader a thumbnail
     * to stretch across the page.
     */
    private fun String.toUrlCandidates(): Sequence<String> = splitToSequence(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { candidate -> candidate.substringBefore(' ') to candidate.descriptorWidth() }
        .sortedByDescending { (_, width) -> width }
        .map { (url, _) -> url }
        .filter(String::isNotEmpty)

    /** The `800w` / `2x` descriptor of a `srcset` entry as a comparable number; 0 when absent. */
    private fun String.descriptorWidth(): Float {
        val descriptor = substringAfter(' ', "").trim().ifEmpty { return 0f }
        val value = descriptor.dropLast(1).toFloatOrNull() ?: return 0f
        return if (descriptor.endsWith('x')) value * DENSITY_TO_WIDTH else value
    }

    /** Puts `2x`-style density descriptors on roughly the same scale as `800w` width ones. */
    private const val DENSITY_TO_WIDTH = 1000f
}
