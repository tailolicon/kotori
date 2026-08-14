package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NearEchoNameGuardTest {

    @Test
    fun `single all-caps name is not replaced by provider typo`() {
        assertTrue(NearEchoNameGuard.preserveSource("YUUKI.", "YUKKI.", "en", "vi"))
        assertTrue(NearEchoNameGuard.preserveSource("MATO!", "MATO!", "en", "vi"))
    }

    @Test
    fun `translated words and other language pairs are untouched`() {
        assertFalse(NearEchoNameGuard.preserveSource("TIME", "THỜI GIAN", "en", "vi"))
        assertFalse(NearEchoNameGuard.preserveSource("WHO", "AI", "en", "vi"))
        assertFalse(NearEchoNameGuard.preserveSource("YUUKI", "YUKKI", "ja", "vi"))
    }
}
