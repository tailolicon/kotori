package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaPipelineTest {

    @Test
    fun `japanese page shaped image uses the manga port`() {
        assertTrue(MangaPipeline.shouldHandle(900, 1300, "ja", "Gemini"))
    }

    @Test
    fun `joined japanese pages still use the manga port`() {
        assertTrue(MangaPipeline.shouldHandle(800, 4000, "ja", "Gemini"))
    }

    @Test
    fun `korean webtoon strip stays on the original path`() {
        assertFalse(MangaPipeline.shouldHandle(800, 4000, "ko", "Gemini"))
    }

    @Test
    fun `regression never takes the manga port`() {
        assertFalse(MangaPipeline.shouldHandle(900, 1300, "ja", "Regression"))
    }

    @Test
    fun `japanese ocr is required before lettering`() {
        assertTrue(MangaPipeline.hasJapaneseDialogue(listOf("...", "幸人もう遅い")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("HELLO", "WAIT")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("あ", "WAIT")))
        assertFalse(MangaPipeline.hasJapaneseDialogue(listOf("漢字하나")))
    }
}
