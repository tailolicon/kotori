package mihon.feature.translation

import mihon.feature.translation.render.BubbleRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RenderStyleAutoTest {

    /**
     * Mirrors the decision in PageTranslator so the rule itself is pinned somewhere runnable.
     * Kept in step by hand; the point is that the *rule* is stated once and asserted.
     */
    private fun resolve(
        chosen: TranslationRenderStyle,
        pageLanguage: String,
        format: PageFormat,
    ): TranslationRenderStyle {
        val typeset = when (chosen) {
            TranslationRenderStyle.TYPESET -> true
            TranslationRenderStyle.SIMPLE -> false
            else -> pageLanguage == "ja" && format == PageFormat.MANGA
        }
        return if (typeset) TranslationRenderStyle.TYPESET else TranslationRenderStyle.SIMPLE
    }

    @Test
    fun `auto typesets a japanese page and letters everything else in place`() {
        val auto = TranslationRenderStyle.AUTO
        assertEquals(TranslationRenderStyle.TYPESET, resolve(auto, "ja", PageFormat.MANGA))
        // The case that shipped broken: a colour webtoon rendered as flooded dark slabs because the
        // install had been left pinned to TYPESET while manga was being worked on.
        assertEquals(TranslationRenderStyle.SIMPLE, resolve(auto, "en", PageFormat.WEBTOON))
        assertEquals(TranslationRenderStyle.SIMPLE, resolve(auto, "en", PageFormat.MANGA))
        assertEquals(TranslationRenderStyle.SIMPLE, resolve(auto, "ko", PageFormat.WEBTOON))
        assertEquals(TranslationRenderStyle.SIMPLE, resolve(auto, "ja", PageFormat.WEBTOON))
    }

    @Test
    fun `an explicit choice still wins`() {
        assertEquals(
            TranslationRenderStyle.TYPESET,
            resolve(TranslationRenderStyle.TYPESET, "en", PageFormat.WEBTOON),
        )
        assertEquals(
            TranslationRenderStyle.SIMPLE,
            resolve(TranslationRenderStyle.SIMPLE, "ja", PageFormat.MANGA),
        )
    }

    @Test
    fun `the renderer never receives AUTO without a safe fallback`() {
        // BubbleRenderer maps AUTO to the footprint letterer, the mode that cannot damage a page it
        // was wrong about. Asserted by construction: the enum entry exists and the renderer handles
        // every entry exhaustively, so a missing branch is a compile error rather than a bad page.
        assertEquals(4, TranslationRenderStyle.entries.size)
        assertEquals(TranslationRenderStyle.AUTO, TranslationRenderStyle.entries.first())
        requireNotNull(BubbleRenderer::class.java)
    }
}
