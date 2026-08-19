package mihon.feature.translation.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiKeyRingTest {

    private var clock = 1_000_000L
    private val ring = GeminiKeyRing { clock }

    @Test
    fun `keys may be entered one per line or comma-separated`() {
        assertEquals(
            listOf("AAA", "BBB", "CCC"),
            ring.parse(" AAA \n BBB,CCC\n\n"),
        )
    }

    @Test
    fun `a repeated key is only tried once`() {
        assertEquals(listOf("AAA"), ring.parse("AAA\nAAA"))
    }

    @Test
    fun `a spent key steps aside for the next one`() {
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", "m", 60)
        assertEquals(listOf("BBB"), ring.available(keys, "m"))
        assertFalse(ring.allParked(keys, "m"))
    }

    @Test
    fun `a parked key comes back when its window passes`() {
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", "m", 60)
        clock += 61_000
        assertEquals(keys, ring.available(keys, "m"))
    }

    @Test
    fun `with every key spent one is still offered so a reset can be noticed`() {
        // Returning nothing here would leave translation off until the app restarted, even after
        // the daily quota had rolled over.
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", "m", 600)
        ring.park("BBB", "m", 1800)
        assertTrue(ring.allParked(keys, "m"))
        assertEquals(listOf("AAA"), ring.available(keys, "m"))
    }

    @Test
    fun `a key that works is un-parked`() {
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", "m", 600)
        ring.release("AAA")
        assertEquals(keys, ring.available(keys, "m"))
    }

    @Test
    fun `no keys means nothing to try`() {
        assertTrue(ring.available(emptyList(), "m").isEmpty())
        assertFalse(ring.allParked(emptyList(), "m"))
    }

    @Test
    fun `a key spent on one model still has its allowance on another`() {
        // Google meters the daily quota per model, so parking the key outright would throw away
        // hundreds of requests the same key still has on a lighter model.
        val keys = listOf("AAA")
        ring.park("AAA", "gemini-3.5-flash", 1800)
        assertTrue(ring.available(keys, "gemini-3.5-flash").isEmpty() || ring.allParked(keys, "gemini-3.5-flash"))
        assertEquals(keys, ring.available(keys, "gemini-3.1-flash-lite"))
        assertFalse(ring.allParked(keys, "gemini-3.1-flash-lite"))
    }
}
