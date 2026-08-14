package mihon.feature.translation.ocr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EllipticalContextRetryPolicyTest {
    @Test
    fun `retries shallow clipped English detector boxes`() {
        assertTrue(EllipticalContextRetryPolicy.shouldRetry("en", 0.151f, 97, 29))
    }

    @Test
    fun `does not broaden ordinary boxes or other source languages`() {
        assertFalse(EllipticalContextRetryPolicy.shouldRetry("en", 0.151f, 97, 60))
        assertFalse(EllipticalContextRetryPolicy.shouldRetry("en", 0.05f, 97, 29))
        assertFalse(EllipticalContextRetryPolicy.shouldRetry("ja", 0.151f, 97, 29))
    }
}
