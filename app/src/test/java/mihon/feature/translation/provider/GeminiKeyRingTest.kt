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
        ring.park("AAA", 60)
        assertEquals(listOf("BBB"), ring.available(keys))
        assertFalse(ring.allParked(keys))
    }

    @Test
    fun `a parked key comes back when its window passes`() {
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", 60)
        clock += 61_000
        assertEquals(keys, ring.available(keys))
    }

    @Test
    fun `with every key spent one is still offered so a reset can be noticed`() {
        // Returning nothing here would leave translation off until the app restarted, even after
        // the daily quota had rolled over.
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", 600)
        ring.park("BBB", 1800)
        assertTrue(ring.allParked(keys))
        assertEquals(listOf("AAA"), ring.available(keys))
    }

    @Test
    fun `a key that works is un-parked`() {
        val keys = listOf("AAA", "BBB")
        ring.park("AAA", 600)
        ring.release("AAA")
        assertEquals(keys, ring.available(keys))
    }

    @Test
    fun `no keys means nothing to try`() {
        assertTrue(ring.available(emptyList()).isEmpty())
        assertFalse(ring.allParked(emptyList()))
    }
}
