package eu.kanade.tachiyomi.network

import okhttp3.Dns
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tries each DNS delegate in order, starting at the last one that worked.
 * Used so Cloudflare can fail over to Google when an ISP blocks one resolver.
 */
class SequentialDns(
    delegates: List<Dns>,
) : Dns {
    val delegates: List<Dns> = delegates.toList()
    private val lastGood = AtomicInteger(0)

    constructor(vararg delegates: Dns) : this(delegates.toList())

    init {
        require(this.delegates.isNotEmpty()) { "At least one DNS delegate is required" }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        var lastError: IOException? = null
        val start = lastGood.get().coerceIn(0, delegates.lastIndex)
        for (offset in delegates.indices) {
            val index = (start + offset) % delegates.size
            try {
                val addresses = delegates[index].lookup(hostname)
                if (addresses.isNotEmpty()) {
                    lastGood.set(index)
                    return addresses
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: UnknownHostException("Unable to resolve $hostname")
    }
}
