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
}
