package mihon.feature.translation.manga

import mihon.feature.translation.PageFormat
import mihon.feature.translation.PageFormatDetector
import mihon.feature.translation.ScriptKind

/**
 * Chooses the dedicated Manga-Translator port versus Kotori's original webtoon path.
 *
 * The decision is made on the *page*, never on a setting — routing on a stored source language is
 * what let a Korean series keep being read as Japanese after the reader moved on from a Japanese
 * one. But it is not made on shape alone either, and that mistake shipped: a webtoon slice is
 * 676x952 and a manga page is 900x1300, the same shape to two decimal places, so "short page" sent
 * English webtoon slices into a pipeline that only knows speech balloons. It filled the panels
 * grey, merged the credits column into one block and pushed text out of its box — every symptom of
 * running a page pipeline over something that is not a page.
 *
 * Three things must all hold:
 *
 *  * **Shape.** The port recovers a balloon and sets the translation inside it. Right for a bound
 *    page, wrong for a strip, whose lettering routinely sits outside any balloon.
 *  * **Script.** Japanese. This is what the port was written for — vertical columns whose recognised
 *    footprint is a sliver no sentence fits into — and everything else is served better by the
 *    original path, which has years of guards for exactly that material.
 *  * **A provider that can read images.** Nothing else in this pipeline reads the balloons.
 */
internal object MangaPipeline {

    const val REGRESSION_PROVIDER = "Regression"

    /**
     * Cheap half of the decision: is it even worth probing this page's script?
     *
     * Kept separate so a webtoon strip never pays for the probe at all.
     */
    fun mayHandle(width: Int, height: Int, providerName: String, providerReadsImages: Boolean): Boolean {
        // The regression suite pins the original path so its goldens keep measuring one pipeline.
        if (providerName == REGRESSION_PROVIDER) return false
        if (!providerReadsImages) return false
        return PageFormatDetector.detect(width, height, ScriptKind.NONE) == PageFormat.MANGA
    }

    fun shouldHandle(
        width: Int,
        height: Int,
        pageScript: String,
        providerName: String,
        providerReadsImages: Boolean,
    ): Boolean = mayHandle(width, height, providerName, providerReadsImages) && pageScript == "ja"

    fun hasJapaneseDialogue(texts: List<String>): Boolean {
        val japanese = texts.filter { MangaOcrPostProcess.looksJapanese(it) }
        if (japanese.isEmpty()) return false
        val kana = japanese.sumOf { line -> line.count { ch -> ch.code in 0x3040..0x30FF } }
        return kana >= 2 || japanese.size >= 2
    }
}
