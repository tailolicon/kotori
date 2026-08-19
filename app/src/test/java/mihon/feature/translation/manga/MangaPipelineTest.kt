package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaPipelineTest {

    private fun route(width: Int, height: Int, script: String, provider: String = "Gemini") =
        MangaPipeline.shouldHandle(width, height, script, provider, providerReadsImages = true)

    @Test
    fun `a japanese page goes to the manga port`() {
        assertTrue(route(900, 1300, "ja"))
        assertTrue(route(2069, 2880, "ja"))
    }

    @Test
    fun `a webtoon slice the same shape as a page does not`() {
        // This shipped broken in 1.0.19. A webtoon slice and a manga page are indistinguishable by
        // shape — these are real fixture sizes — so routing on shape alone put credits pages and
        // English slices through the balloon filler, which merged their columns into one grey block
        // and pushed text out of its box.
        assertFalse(route(676, 952, "en"), "676x952 English slice")
        assertFalse(route(900, 1478, "en"), "900x1478 English slice")
        assertFalse(route(900, 1300, "ko"), "Korean page-shaped slice")
        assertFalse(route(900, 1300, "zh"), "Chinese page-shaped slice")
    }

    @Test
    fun `a page whose script could not be probed stays on the original path`() {
        assertFalse(route(900, 1300, ""))
    }

    @Test
    fun `webtoon strips never reach the manga port whatever they are written in`() {
        assertFalse(route(800, 4000, "ja"))
        assertFalse(route(1280, 12755, "ja"))
        assertFalse(route(900, 1600, "ja"))
    }

    @Test
    fun `a provider that cannot read images cannot use this port`() {
        // Nothing else in this pipeline reads the balloons, so without a vision provider the page
        // has to go to the path that owns the on-device recogniser.
        assertFalse(MangaPipeline.shouldHandle(900, 1300, "ja", "Groq", providerReadsImages = false))
        assertFalse(MangaPipeline.mayHandle(900, 1300, "Google Dich", providerReadsImages = false))
    }

    @Test
    fun `regression never takes the manga port`() {
        assertFalse(route(900, 1300, "ja", provider = "Regression"))
        assertFalse(MangaPipeline.mayHandle(900, 1300, "Regression", providerReadsImages = true))
    }

    @Test
    fun `only a page-shaped image is worth probing`() {
        // The probe costs a band read, so a strip must not pay for it.
        assertTrue(MangaPipeline.mayHandle(900, 1300, "Gemini", providerReadsImages = true))
        assertFalse(MangaPipeline.mayHandle(1280, 12755, "Gemini", providerReadsImages = true))
    }

    @Test
    fun `japanese dialogue detection still recognises kana`() {
        assertTrue(MangaPipeline.hasJapaneseDialogue(listOf("...", "\u5E78\u4EBA\u3082\u3046\u9045\u3044")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("HELLO", "WAIT")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("\u3042", "WAIT")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("\u6F22\u5B57\ud558\ub098")))
    }
}
