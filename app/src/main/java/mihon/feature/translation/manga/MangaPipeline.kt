package mihon.feature.translation.manga

import mihon.feature.translation.PageFormat
import mihon.feature.translation.PageFormatDetector
import mihon.feature.translation.ScriptKind

/**
 * Chooses the dedicated Manga-Translator port versus Kotori's original webtoon path.
 *
 * The decision is made on the *page*, never on a setting. Routing on a stored source language is
 * what let a Korean series keep being read as Japanese after the reader moved on from a Japanese
 * one: the preference belonged to the app, not to the series in front of it.
 *
 * Two things decide it instead:
 *
 *  * **Shape.** The port is the Python repo's page pipeline — detect balloons, flood them, set the
 *    translation in the balloon. That is right for a bound page and wrong for a webtoon strip, whose
 *    lettering routinely sits outside any balloon (status windows, narration bands, captions over
 *    art) and which the original path already handles well.
 *  * **Whether the provider can read.** Balloon geometry alone is not a translation; something has
 *    to say what each balloon holds. The vision providers do that in the same call that translates,
 *    for any language on the page. Without one, the original path's on-device recogniser is the only
 *    reader available, so the page goes there.
 */
internal object MangaPipeline {

    const val REGRESSION_PROVIDER = "Regression"

    fun shouldHandle(
        width: Int,
        height: Int,
        providerName: String,
        providerReadsImages: Boolean,
    ): Boolean {
        // The regression suite pins the original path so its goldens keep measuring one pipeline.
        if (providerName == REGRESSION_PROVIDER) return false
        if (!providerReadsImages) return false
        return PageFormatDetector.detect(width, height, ScriptKind.NONE) == PageFormat.MANGA
    }

    fun hasJapaneseDialogue(texts: List<String>): Boolean {
        val japanese = texts.filter { MangaOcrPostProcess.looksJapanese(it) }
        if (japanese.isEmpty()) return false
        val kana = japanese.sumOf { line -> line.count { ch -> ch.code in 0x3040..0x30FF } }
        return kana >= 2 || japanese.size >= 2
    }
}
