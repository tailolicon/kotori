package mihon.feature.translation.offline

import mihon.feature.translation.provider.TranslationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflinePromptsTest {

    private val vi = TranslationContext(sourceLanguage = "ja", targetLanguage = "vi")
    private val en = TranslationContext(sourceLanguage = "ja", targetLanguage = "en")

    @Test
    fun `single line uses official benchmarked English instruction`() {
        val prompt = OfflinePrompts.singleLine(vi, "走るな！")
        assertTrue(prompt.startsWith("Translate the following segment into Vietnamese"))
        assertTrue(prompt.contains("without additional explanation."))
        assertTrue(prompt.contains("走るな！"))
        assertFalse(prompt.contains("Dịch:"))
        assertFalse(prompt.contains(OfflineModelSpec.HY_BOS))
        assertFalse(prompt.contains(OfflineModelSpec.HY_USER))
    }

    @Test
    fun `single line does not pre-wrap chat template tokens`() {
        val prompt = OfflinePrompts.singleLine(en, "Hello")
        assertEquals(
            "Translate the following segment into English, without additional explanation.\n\nHello",
            prompt,
        )
    }

    @Test
    fun `hy template helper is available for tests and wraps once`() {
        val wrapped = OfflinePrompts.applyHyMtChatTemplate("body")
        assertTrue(wrapped.startsWith(OfflineModelSpec.HY_BOS))
        assertTrue(wrapped.contains(OfflineModelSpec.HY_USER + "body"))
        assertTrue(wrapped.endsWith(OfflineModelSpec.HY_ASSISTANT))
        // Production path must not double-wrap: singleLine stays raw.
        assertFalse(OfflinePrompts.singleLine(en, "x").contains(OfflineModelSpec.HY_BOS))
    }

    @Test
    fun `cleanBubble strips label and arrow echo and keeps first line`() {
        assertEquals("Chạy đi!", OfflinePrompts.cleanBubble("Dịch: Chạy đi!", "走れ"))
        assertEquals(
            "Chạy đi!",
            OfflinePrompts.cleanBubble("\"走れ\" -> \"Chạy đi!\"", "走れ"),
        )
        assertEquals("Chạy đi!", OfflinePrompts.cleanBubble("Chạy đi!\n(extra note)", "走れ"))
    }

    @Test
    fun `cleanProse keeps multiline paragraphs`() {
        val raw = "Paragraph one.\n\nParagraph two."
        assertEquals(raw, OfflinePrompts.cleanProse(raw, "src"))
    }

    @Test
    fun `cleanCompletion does not invent text from blank`() {
        assertEquals("", OfflinePrompts.cleanBubble("   ", "x"))
    }
}
