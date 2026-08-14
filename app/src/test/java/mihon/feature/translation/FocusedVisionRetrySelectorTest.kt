package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FocusedVisionRetrySelectorTest {

    @Test
    fun `only blank speech slots are retried`() {
        assertEquals(
            listOf(0, 3),
            FocusedVisionRetrySelector.indices(
                translations = listOf("", "", "Đã dịch", ""),
                speechBoxes = listOf(true, false, true, true),
                limit = 12,
            ),
        )
    }

    @Test
    fun `retry count is bounded`() {
        assertEquals(
            listOf(0, 1),
            FocusedVisionRetrySelector.indices(
                translations = List(5) { "" },
                speechBoxes = List(5) { true },
                limit = 2,
            ),
        )
    }

    @Test
    fun `suspected source echo is retried even when nonblank`() {
        assertEquals(
            listOf(1),
            FocusedVisionRetrySelector.indices(
                translations = listOf("Đã dịch", "YES."),
                speechBoxes = listOf(true, true),
                suspectedEchoes = listOf(false, true),
                limit = 12,
            ),
        )
    }

    @Test
    fun `echoed OCR fallback is retried even when represented as a text block`() {
        assertEquals(
            listOf(0),
            FocusedVisionRetrySelector.indices(
                translations = listOf("YES."),
                speechBoxes = listOf(false),
                suspectedEchoes = listOf(true),
                limit = 12,
            ),
        )
    }
}
