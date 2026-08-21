package mihon.feature.translation.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeminiResponseTest {

    @Test
    fun `candidateText skips thought parts`() {
        val body = """
            {"candidates":[{"content":{"parts":[
              {"thought":true,"text":"I should output JSON"},
              {"text":"[\"xin chao\"]"}
            ]}}]}
        """.trimIndent()
        assertEquals("[\"xin chao\"]", GeminiResponse.candidateText(body))
    }

    @Test
    fun `a leaked object gives up its translation, not its braces`() {
        val leaked = """{"id": 9, "text": "HAAAH, THAT WAS GOOD.", "translation": "Haa, sướng thật."}"""
        assertEquals("Haa, sướng thật.", GeminiResponse.sanitizeTranslation(leaked))
    }

    @Test
    fun `an object with no translation field is answered with nothing`() {
        val leaked = """{"id": 4, "text": "WAIT!"}"""
        assertEquals("", GeminiResponse.sanitizeTranslation(leaked))
    }

    @Test
    fun `an ordinary line is left alone`() {
        assertEquals("Dừng lại đi!", GeminiResponse.sanitizeTranslation("  Dừng lại đi!  "))
    }
}
