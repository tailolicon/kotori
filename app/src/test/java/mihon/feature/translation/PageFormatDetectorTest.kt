package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageFormatDetectorTest {

    @Test
    fun `a tall strip is a webtoon regardless of script`() {
        assertEquals(PageFormat.WEBTOON, PageFormatDetector.detect(800, 4000, ScriptKind.KOREAN))
        assertEquals(PageFormat.WEBTOON, PageFormatDetector.detect(800, 4000, ScriptKind.LATIN))
    }

    @Test
    fun `a short japanese page is manga`() {
        assertEquals(PageFormat.MANGA, PageFormatDetector.detect(900, 1300, ScriptKind.JAPANESE))
    }

    @Test
    fun `webtoon companions include korean and latin`() {
        val companions = PageFormatDetector.companionScripts(PageFormat.WEBTOON, "ko")
        assertEquals(listOf("en", "zh", "ja"), companions)
    }

    @Test
    fun `manga companions start with japanese`() {
        val companions = PageFormatDetector.companionScripts(PageFormat.MANGA, "en")
        assertEquals(listOf("ja", "zh"), companions)
    }

    @Test
    fun `a short korean page that is not a strip is still a webtoon`() {
        assertEquals(PageFormat.WEBTOON, PageFormatDetector.detect(720, 1280, ScriptKind.KOREAN))
    }

    @Test
    fun `narrow rescue is refused on webtoon panels but kept on manga balloons`() {
        assertTrue(PageFormatDetector.refuseNarrowRescue(PageFormat.WEBTOON, 200, 1000))
        assertFalse(PageFormatDetector.refuseNarrowRescue(PageFormat.MANGA, 200, 1000))
    }
}
