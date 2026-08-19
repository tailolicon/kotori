package mihon.feature.translation.manga

import mihon.feature.translation.PageFormat
import mihon.feature.translation.PageFormatDetector
import mihon.feature.translation.ScriptKind
import mihon.feature.translation.ScriptKindDetector

/**
 * Chooses the dedicated Manga-Translator port versus Kotori's original SIMPLE/webtoon path.
 *
 * Regression stays on the original path so manhwa goldens do not move. Japanese source (including
 * prefetch-joined pages) uses this port; a page still falls back if manga-ocr finds no dialogue.
 */
internal object MangaPipeline {

    const val REGRESSION_PROVIDER = "Regression"

    fun shouldHandle(
        width: Int,
        height: Int,
        sourceLanguage: String,
        providerName: String,
    ): Boolean {
        if (providerName == REGRESSION_PROVIDER) return false
        // Prefetch joins several manga pages into one tall bitmap. That strip must still use
        // the manga port — YOLO already slices long images the same way the Python repo does.
        if (sourceLanguage == "ja" || sourceLanguage.isBlank() || sourceLanguage == "auto") return true
        val script = ScriptKindDetector.ofLanguage(sourceLanguage)
        return PageFormatDetector.detect(width, height, script) == PageFormat.MANGA
    }

    fun hasJapaneseDialogue(texts: List<String>): Boolean {
        val japanese = texts.filter { MangaOcrPostProcess.looksJapanese(it) }
        if (japanese.isEmpty()) return false
        val kana = japanese.sumOf { line -> line.count { ch -> ch.code in 0x3040..0x30FF } }
        return kana >= 2 || japanese.size >= 2
    }
}
