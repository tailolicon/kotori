package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationWorkGateTest {

    @Test
    fun `switching manga invalidates the previous generation`() {
        val gate = TranslationWorkGate()
        gate.begin(10L)
        val first = gate.generation
        assertTrue(gate.allows(10L, first))

        gate.begin(20L)
        assertFalse(gate.allows(10L, first))
        assertTrue(gate.allows(20L, gate.generation))
    }

    @Test
    fun `disabling the active series stops in-flight work`() {
        val gate = TranslationWorkGate()
        gate.begin(10L)
        val generation = gate.generation
        gate.cancel(10L)
        assertFalse(gate.allows(10L, generation))
    }

    @Test
    fun `cancelling a different series does not disturb the active one`() {
        val gate = TranslationWorkGate()
        gate.begin(10L)
        val generation = gate.generation
        gate.cancel(99L)
        assertTrue(gate.allows(10L, generation))
    }

    @Test
    fun `invalidating in-flight work rejects the old generation on the same series`() {
        val gate = TranslationWorkGate()
        gate.begin(10L)
        val generation = gate.generation
        gate.invalidateInFlight()
        assertFalse(gate.allows(10L, generation))
        assertTrue(gate.allows(10L, gate.generation))
        assertTrue(gate.activeMangaId == 10L)
    }

    @Test
    fun `re-beginning the same series does not bump the generation`() {
        val gate = TranslationWorkGate()
        gate.begin(10L)
        val generation = gate.generation
        gate.begin(10L)
        assertTrue(gate.allows(10L, generation))
    }

    @Test
    fun `after a switch leftover work for the previous series is not current`() {
        val gate = TranslationWorkGate()
        gate.begin(10L)
        gate.begin(20L)
        assertTrue(gate.activeMangaId == 20L)
        assertFalse(gate.activeMangaId == 10L)
    }
}
