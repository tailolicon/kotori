package mihon.feature.translation.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeskewRetryPolicyTest {

    @Test
    fun `measured slant is deskewed in the opposite direction`() {
        assertEquals(listOf(18f), DeskewRetryPolicy.rotations("eivOHM", -18f))
    }

    @Test
    fun `reversed mixed-case garbage gets two bounded fallback angles`() {
        assertEquals(listOf(20f, -20f), DeskewRetryPolicy.rotations("eivOHM", 0f))
    }

    @Test
    fun `normal dialogue and names do not pay for retries`() {
        assertEquals(emptyList<Float>(), DeskewRetryPolicy.rotations("WHOA!", -20f))
        assertEquals(emptyList<Float>(), DeskewRetryPolicy.rotations("KYOUKA", 0f))
    }
}
