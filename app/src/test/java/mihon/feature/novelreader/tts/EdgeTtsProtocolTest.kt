package mihon.feature.novelreader.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EdgeTtsProtocolTest {

    /**
     * Pinned against an independent implementation (the reference `edge-tts` algorithm) for a fixed
     * instant, so a refactor that changes the rounding or the epoch math fails loudly instead of
     * producing hashes the server silently rejects.
     */
    @Test
    fun `proof-of-time hash matches the reference implementation`() {
        assertEquals(
            "161C0F3E3583832F9CABD054484B57FF2D4A6EBAA27AAD6AEA2608F0616C9B99",
            EdgeTtsProtocol.secMsGec(unixMs = 1_753_000_000_000),
        )
    }

    @Test
    fun `hash is constant within a five-minute window`() {
        val base = 1_753_000_000_000
        // 1753000000s rounds into a window that began 100s earlier, so 100s of headroom remain.
        assertEquals(EdgeTtsProtocol.secMsGec(base), EdgeTtsProtocol.secMsGec(base + 99_000))
        assertFalse(EdgeTtsProtocol.secMsGec(base) == EdgeTtsProtocol.secMsGec(base + 301_000))
    }

    @Test
    fun `rate becomes a signed percentage`() {
        assertEquals("+0%", EdgeTtsProtocol.ratePercent(1f))
        assertEquals("+25%", EdgeTtsProtocol.ratePercent(1.25f))
        assertEquals("-10%", EdgeTtsProtocol.ratePercent(0.9f))
        assertEquals("+100%", EdgeTtsProtocol.ratePercent(2f))
    }

    @Test
    fun `ssml escapes the chapter text but not the markup`() {
        val ssml = EdgeTtsProtocol.ssml(
            text = """Anh nói: "1 < 2 & 3 > 2" — chuyện của Q'anh.""",
            voice = "vi-VN-HoaiMyNeural",
            rate = 1f,
        )
        assertTrue("&quot;1 &lt; 2 &amp; 3 &gt; 2&quot;" in ssml)
        assertTrue("Q&apos;anh" in ssml)
        assertTrue("<voice name='vi-VN-HoaiMyNeural'>" in ssml)
        // Raw specials from the text must never survive into the markup stream.
        assertFalse(""""1 < 2""" in ssml)
    }

    @Test
    fun `connection url carries the token and both drm parameters`() {
        val url = EdgeTtsProtocol.connectionUrl(unixMs = 1_753_000_000_000)
        assertTrue("TrustedClientToken=${EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN}" in url)
        assertTrue("Sec-MS-GEC=161C0F3E3583832F9CABD054484B57FF2D4A6EBAA27AAD6AEA2608F0616C9B99" in url)
        assertTrue("Sec-MS-GEC-Version=1-" in url)
        assertTrue("ConnectionId=" in url)
    }

    /**
     * The service refuses a handshake claiming a stale Edge build with `403 Forbidden`, which is
     * exactly what an outdated constant here looks like from the app — so the version is pinned
     * rather than left to drift silently.
     */
    @Test
    fun `claims a current edge build`() {
        assertEquals("1-143.0.3650.75", EdgeTtsProtocol.secMsGecVersion())
        assertTrue("Edg/143.0.0.0" in EdgeTtsProtocol.USER_AGENT)
    }

    /**
     * The SSML frame's timestamp carries a trailing `Z` on top of an already-complete date — Edge
     * sends it that way and the service expects it.
     */
    @Test
    fun `ssml frame keeps the malformed timestamp the service expects`() {
        val frame = EdgeTtsProtocol.ssmlMessage("abc123", "<speak/>")
        assertTrue("X-RequestId:abc123" in frame)
        assertTrue("Path:ssml" in frame)
        assertTrue(Regex("X-Timestamp:.*Coordinated Universal Time\\)Z\r\n").containsMatchIn(frame))
    }
}
