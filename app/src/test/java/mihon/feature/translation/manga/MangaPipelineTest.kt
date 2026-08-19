package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaPipelineTest {

    @Test
    fun `a page-shaped image goes to the manga port whatever language it is in`() {
        // The routing question is "is this a bound page", not "is this Japanese". Deciding it from a
        // stored source language is what let a Korean series keep being read as Japanese.
        assertTrue(MangaPipeline.shouldHandle(900, 1300, "Gemini", providerReadsImages = true))
        assertTrue(MangaPipeline.shouldHandle(2069, 2880, "Gemini", providerReadsImages = true))
    }

    @Test
    fun `webtoon strips stay on the original path`() {
        assertFalse(MangaPipeline.shouldHandle(800, 4000, "Gemini", providerReadsImages = true))
        assertFalse(MangaPipeline.shouldHandle(1280, 12755, "Gemini", providerReadsImages = true))
        // A stack of slices is still a strip once joined, and even one tall slice is not a page.
        assertFalse(MangaPipeline.shouldHandle(900, 1600, "Gemini", providerReadsImages = true))
    }

    @Test
    fun `a provider that cannot read images cannot use this port`() {
        // Nothing else in this pipeline reads the balloons, so without a vision provider the page
        // has to go to the path that owns the on-device recogniser.
        assertFalse(MangaPipeline.shouldHandle(900, 1300, "Groq", providerReadsImages = false))
        assertFalse(MangaPipeline.shouldHandle(900, 1300, "Google Dịch", providerReadsImages = false))
    }

    @Test
    fun `regression never takes the manga port`() {
        assertFalse(MangaPipeline.shouldHandle(900, 1300, "Regression", providerReadsImages = true))
    }

    @Test
    fun `japanese dialogue detection still recognises kana`() {
        assertTrue(MangaPipeline.hasJapaneseDialogue(listOf("...", "幸人もう遅い")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("HELLO", "WAIT")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("あ", "WAIT")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("漢字하나")))
    }
}
