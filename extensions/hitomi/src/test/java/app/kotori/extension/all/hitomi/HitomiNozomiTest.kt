package app.kotori.extension.all.hitomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HitomiNozomiTest {

    @Test
    fun `range for page 2 continues after the first 25 ids`() {
        assertEquals(0 to 99, HitomiNozomi.byteRange(1))
        assertEquals(100 to 199, HitomiNozomi.byteRange(2))
    }

    @Test
    fun `ids are big-endian 32-bit`() {
        val bytes = byteArrayOf(0x00, 0x01, 0xE2.toByte(), 0x40, 0x00, 0x00, 0x00, 0x02)
        assertEquals(listOf(123456L, 2L), HitomiNozomi.parseIds(bytes))
    }

    @Test
    fun `full-file response is sliced so later pages still work`() {
        val all = ByteArray(400) { i -> (i % 256).toByte() }
        val (page2, hasNext) = HitomiNozomi.slicePage(all, 2, contentRange = null)
        assertEquals(25, page2.size)
        assertTrue(hasNext)
        val last = HitomiNozomi.slicePage(all, 4, contentRange = null)
        assertFalse(last.second)
    }

    @Test
    fun `content-range on the last slice ends the list`() {
        assertFalse(HitomiNozomi.hasNextFromRange(25, "bytes 3900-3999/4000"))
        assertTrue(HitomiNozomi.hasNextFromRange(25, "bytes 0-99/4000"))
    }

    @Test
    fun `a full ranged page without Content-Range is not treated as the end`() {
        val onePage = ByteArray(HitomiNozomi.PAGE_SIZE * HitomiNozomi.ID_BYTES) { 1 }
        val (ids, hasNext) = HitomiNozomi.slicePage(onePage, 1, contentRange = null)
        assertEquals(25, ids.size)
        assertTrue(hasNext)
    }

    @Test
    fun `a short last page without Content-Range ends the list`() {
        val short = ByteArray(10 * HitomiNozomi.ID_BYTES) { 1 }
        val (_, hasNext) = HitomiNozomi.slicePage(short, 1, contentRange = null)
        assertFalse(hasNext)
    }
}
