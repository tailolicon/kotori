package mihon.feature.translation.ocr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlankOcrRetryPolicyTest {

    @Test
    fun `blank high-confidence or tiny English proposals get contrast retry`() {
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.254f, 60, 46, ""))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.074f, 97, 35, ""))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.060f, 111, 36, ""))
        assertFalse(BlankOcrRetryPolicy.shouldRetry("en", 0.05f, 97, 35, ""))
        assertFalse(BlankOcrRetryPolicy.shouldRetry("ja", 0.30f, 60, 46, ""))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.30f, 60, 46, "READ"))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.30f, 60, 46, "O"))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.18f, 60, 46, "ANDALO"))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.074f, 97, 35, "MHM"))
        assertFalse(BlankOcrRetryPolicy.shouldRetry("en", 0.074f, 97, 35, "YOU"))
        assertTrue(BlankOcrRetryPolicy.shouldRetry("en", 0.074f, 97, 35, "WITHOUT"))
    }

    @Test
    fun `low-confidence recovery requires known utterance`() {
        assertTrue(BlankOcrRetryPolicy.accept(0.074f, "YOU...!"))
        assertTrue(BlankOcrRetryPolicy.accept(0.102f, "I..."))
        assertFalse(BlankOcrRetryPolicy.accept(0.074f, "MHM~~"))
        assertTrue(BlankOcrRetryPolicy.accept(0.254f, "AND ALSO"))
        assertTrue(BlankOcrRetryPolicy.acceptFallback(0.074f, "Youz!"))
        assertTrue(BlankOcrRetryPolicy.acceptFallback(0.254f, "AND ASO"))
        assertTrue(BlankOcrRetryPolicy.acceptFallback(0.18f, "AND ASO"))
        assertFalse(BlankOcrRetryPolicy.acceptFallback(0.074f, "AND ASO"))
        assertFalse(BlankOcrRetryPolicy.acceptFallback(0.254f, "al Acl"))
    }
}
