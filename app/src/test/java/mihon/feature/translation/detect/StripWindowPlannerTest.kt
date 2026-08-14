package mihon.feature.translation.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StripWindowPlannerTest {

    @Test
    fun `short manga page keeps one unchanged detector window`() {
        assertEquals(listOf(0), StripWindowPlanner.starts(1200, 1200, 800))
    }

    @Test
    fun `long manhwa strip aligns windows to both edges`() {
        val starts = StripWindowPlanner.starts(16000, 1200, 800)

        assertEquals(0, starts.first())
        assertEquals(14800, starts.last())
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a <= 400 })
    }

    @Test
    fun `ordinary tall balloon is complete in at least one window`() {
        val starts = StripWindowPlanner.starts(16000, 1200, 800)

        for (top in 0..15200 step 37) {
            val bottom = top + 800
            assertTrue(starts.any { start -> top >= start && bottom <= start + 1200 })
        }
    }
}
