package eu.kanade.tachiyomi.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

class SequentialDnsTest {

    private val cloudflare = InetAddress.getByName("1.1.1.1")
    private val google = InetAddress.getByName("8.8.8.8")

    @Test
    fun `returns the first non-empty result`() {
        val dns = SequentialDns(
            FakeDns(listOf(cloudflare)),
            FakeDns(listOf(google)),
        )

        assertEquals(listOf(cloudflare), dns.lookup("manga18fx.com"))
    }

    @Test
    fun `skips a resolver that throws and uses the next`() {
        val dns = SequentialDns(
            FakeDns(error = UnknownHostException("cloudflare blocked")),
            FakeDns(listOf(google)),
        )

        assertEquals(listOf(google), dns.lookup("manga18fx.com"))
    }

    @Test
    fun `skips an empty answer and uses the next`() {
        val dns = SequentialDns(
            FakeDns(emptyList()),
            FakeDns(listOf(google)),
        )

        assertEquals(listOf(google), dns.lookup("manga18fx.com"))
    }

    @Test
    fun `rethrows the last failure when every resolver fails`() {
        val last = UnknownHostException("google blocked")
        val dns = SequentialDns(
            FakeDns(error = UnknownHostException("cloudflare blocked")),
            FakeDns(error = last),
        )

        val thrown = assertThrows(UnknownHostException::class.java) {
            dns.lookup("manga18fx.com")
        }
        assertSame(last, thrown)
    }

    @Test
    fun `throws when every resolver returns nothing`() {
        val dns = SequentialDns(FakeDns(emptyList()), FakeDns(emptyList()))

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("manga18fx.com")
        }
    }

    @Test
    fun `later lookups start at the last resolver that worked`() {
        val cloudflareDns = FakeDns(error = UnknownHostException("cloudflare blocked"))
        val dns = SequentialDns(cloudflareDns, FakeDns(listOf(google)))

        assertEquals(listOf(google), dns.lookup("manga18fx.com"))
        assertEquals(listOf(google), dns.lookup("cdn.manga18fx.com"))
        assertEquals(1, cloudflareDns.lookups)
    }

    @Test
    fun `requires at least one delegate`() {
        assertThrows(IllegalArgumentException::class.java) {
            SequentialDns(emptyList())
        }
    }

    @Test
    fun `cloudflare plus google is the built-in default`() {
        val client = OkHttpClient.Builder().applyDohProvider(-1).build()

        assertInstanceOf(SequentialDns::class.java, client.dns)
        val delegates = (client.dns as SequentialDns).delegates
        assertTrue(delegates.size >= 2)
    }

    @Test
    fun `system dns stays available as an explicit choice`() {
        val client = OkHttpClient.Builder().applyDohProvider(PREF_DOH_SYSTEM).build()
        assertEquals(Dns.SYSTEM, client.dns)
    }

    private class FakeDns(
        private val addresses: List<InetAddress> = emptyList(),
        private val error: Exception? = null,
    ) : Dns {
        var lookups = 0
            private set

        override fun lookup(hostname: String): List<InetAddress> {
            lookups++
            if (error != null) throw error
            return addresses
        }
    }
}
