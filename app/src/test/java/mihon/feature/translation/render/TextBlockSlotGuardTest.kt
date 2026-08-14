package mihon.feature.translation.render

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextBlockSlotGuardTest {

    @Test
    fun `shallow cover banner uses its whole cleared rectangle`() {
        assertTrue(TextBlockSlotGuard.useWholeFlatBounds(true, width = 188, height = 60))
    }

    @Test
    fun `speech balloon and tall caption keep shape-aware slotting`() {
        assertFalse(TextBlockSlotGuard.useWholeFlatBounds(false, width = 188, height = 60))
        assertFalse(TextBlockSlotGuard.useWholeFlatBounds(true, width = 120, height = 180))
    }
}
