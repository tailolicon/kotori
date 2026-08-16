package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException

class HttpsPortFallbackTest {

    private val manga18 = "https://manga18fx.com/home?page=1".toHttpUrl()

    @Test
    fun `retries https 443 after a connection reset`() {
        assertTrue(HttpsPortFallback.shouldRetry(manga18, SocketException("Connection reset")))
    }

    @Test
    fun `retries when reset is wrapped in an SSL handshake failure`() {
        val error = SSLHandshakeException("handshake").initCause(SocketException("Connection reset")) as Exception
        assertTrue(HttpsPortFallback.shouldRetry(manga18, error as java.io.IOException))
    }

    @Test
    fun `does not retry plain http or a non-reset error`() {
        assertFalse(
            HttpsPortFallback.shouldRetry("http://manga18fx.com/".toHttpUrl(), SocketException("Connection reset")),
        )
        assertFalse(HttpsPortFallback.shouldRetry(manga18, SocketException("Network unreachable")))
    }

    @Test
    fun `does not retry when already on a fallback port`() {
        val already = manga18.newBuilder().port(8443).build()
        assertFalse(HttpsPortFallback.shouldRetry(already, SocketException("Connection reset")))
    }

    @Test
    fun `rewrites only the port and keeps path and query`() {
        val rewritten = HttpsPortFallback.rewritePort(manga18, 8443)
        assertEquals(8443, rewritten.port)
        assertEquals("/home", rewritten.encodedPath)
        assertEquals("page=1", rewritten.query)
        assertEquals("manga18fx.com", rewritten.host)
    }
}
