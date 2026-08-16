package eu.kanade.tachiyomi.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class ClientHelloSplitterTest {

    /** Records each write as its own entry so we can see where the segment boundaries fall. */
    private class RecordingStream : OutputStream() {
        val writes = mutableListOf<ByteArray>()
        private val all = ByteArrayOutputStream()

        override fun write(b: Int) {
            writes += byteArrayOf(b.toByte())
            all.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writes += b.copyOfRange(off, off + len)
            all.write(b, off, len)
        }

        fun joined(): ByteArray = all.toByteArray()
    }

    private fun hello(size: Int) = ByteArray(size) { it.toByte() }

    @Test
    fun `first write leaves the leading byte alone in its own segment`() {
        val sink = RecordingStream()
        SplittingOutputStream(sink, 1).write(hello(300))

        assertEquals(listOf(1, 299), sink.writes.map { it.size })
    }

    @Test
    fun `splitting does not alter the bytes on the wire`() {
        val sink = RecordingStream()
        val payload = hello(517)
        SplittingOutputStream(sink, 1).write(payload)

        assertEquals(payload.toList(), sink.joined().toList())
    }

    @Test
    fun `later writes pass through untouched`() {
        val sink = RecordingStream()
        val stream = SplittingOutputStream(sink, 1)
        stream.write(hello(300))
        stream.write(hello(200))

        assertEquals(listOf(1, 299, 200), sink.writes.map { it.size })
    }

    @Test
    fun `a write no longer than the split point is sent whole`() {
        val sink = RecordingStream()
        val stream = SplittingOutputStream(sink, 4)
        stream.write(hello(4))
        stream.write(hello(300))

        assertEquals(listOf(4, 300), sink.writes.map { it.size })
    }

    @Test
    fun `a single byte first write still counts as the first write`() {
        val sink = RecordingStream()
        val stream = SplittingOutputStream(sink, 1)
        stream.write(0x16)
        stream.write(hello(300))

        assertEquals(listOf(1, 300), sink.writes.map { it.size })
    }

    @Test
    fun `honours a larger split point`() {
        val sink = RecordingStream()
        SplittingOutputStream(sink, 40).write(hello(517))

        assertEquals(listOf(40, 477), sink.writes.map { it.size })
    }
}
