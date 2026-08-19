package mihon.feature.translation

/**
 * Which kind of page this is, decided from geometry and script — not from the series title.
 *
 * Manga pages are short and often vertical Japanese. Manhwa/manhua arrive as tall colour strips
 * (or a stack of slices) in Korean, Chinese or a Latin scanlation. The OCR recogniser has to
 * follow that, or a mixed Spanish/Korean strip is read as one script and the other is dropped.
 */
enum class PageFormat {
    MANGA,
    WEBTOON,
}

internal object PageFormatDetector {

    fun detect(width: Int, height: Int, script: ScriptKind): PageFormat {
        if (width <= 0 || height <= 0) return PageFormat.MANGA
        val aspect = height.toFloat() / width
        // A stack of webtoon slices or a single long strip. Manga pages sit well below this.
        if (aspect >= STRIP_ASPECT) return PageFormat.WEBTOON
        return when (script) {
            ScriptKind.JAPANESE -> PageFormat.MANGA
            ScriptKind.KOREAN, ScriptKind.CHINESE, ScriptKind.LATIN ->
                if (aspect >= WEBTOON_PAGE_ASPECT) PageFormat.WEBTOON else PageFormat.MANGA
            ScriptKind.NONE -> if (aspect >= WEBTOON_PAGE_ASPECT) PageFormat.WEBTOON else PageFormat.MANGA
        }
    }

    /**
     * Webtoon status panels produce thin overlapping detections that must not be re-read.
     * A manga balloon is often well under a quarter of the page wide — the same floor would
     * refuse every real oval and leave the page untranslated.
     */
    fun refuseNarrowRescue(format: PageFormat, balloonWidth: Int, pageWidth: Int): Boolean {
        if (format != PageFormat.WEBTOON || pageWidth <= 0) return false
        return balloonWidth < pageWidth * WEBTOON_RESCUE_WIDTH_RATIO
    }

    /** Recognisers to try, in order, after the page's own script. */
    fun companionScripts(format: PageFormat, primary: String): List<String> = when (format) {
        PageFormat.MANGA -> listOf("ja", "zh", "en").filter { it != primary }
        PageFormat.WEBTOON -> listOf("ko", "en", "zh", "ja").filter { it != primary }
    }

    private const val STRIP_ASPECT = 2.4f
    private const val WEBTOON_PAGE_ASPECT = 1.7f
    private const val WEBTOON_RESCUE_WIDTH_RATIO = 0.25f
}
