package mihon.feature.translation.provider

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoSourceLanguageTest {

    @Test
    fun `an undeclared source drops the clause instead of naming itself`() {
        val auto = TranslationContext(TranslationContext.AUTO, "vi")
        assertTrue(auto.isSourceAuto)
        assertFalse(auto.sourceClauseVi().contains("auto"))
        assertTrue(auto.sourceClauseVi().isEmpty())
        assertTrue(auto.sourceClauseEn().isEmpty())
    }

    @Test
    fun `a blank source is auto too`() {
        assertTrue(TranslationContext("", "vi").isSourceAuto)
    }

    @Test
    fun `a declared source is still named`() {
        val japanese = TranslationContext("ja", "vi")
        assertFalse(japanese.isSourceAuto)
        assertTrue(japanese.sourceClauseVi().contains("Japanese"))
        assertTrue(japanese.sourceClauseEn().startsWith("from "))
    }

    @Test
    fun `the vision prompt never tells the model the source is called auto`() {
        val prompt = TranslationPrompts.visionBubbles(
            TranslationContext(TranslationContext.AUTO, "vi"),
            imageWidth = 900,
            imageHeight = 1300,
            geometry = "1. (x1=0, y1=0, x2=10, y2=10)",
            boxCount = 1,
        )
        assertFalse(prompt.contains("auto"))
        assertTrue(prompt.contains("tiếng Việt"))
    }
}
