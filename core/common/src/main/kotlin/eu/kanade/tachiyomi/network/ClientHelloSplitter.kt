package eu.kanade.tachiyomi.network

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Some ISPs read the SNI out of the first packet of a TLS handshake and inject a TCP reset
 * when it names a blocked host. Writing the ClientHello as two segments leaves the name
 * straddling a packet boundary, which is enough for those filters to miss it.
 *
 * DNS-over-HTTPS does not help against this: the name is still in the clear on the wire.
 */
class ClientHelloSplittingSocketFactory(
    private val splitAt: Int = DEFAULT_SPLIT_AT,
) : SocketFactory() {

    override fun createSocket(): Socket = SplittingSocket(splitAt)

    override fun createSocket(host: String, port: Int): Socket =
        open(InetSocketAddress(host, port), null)

    override fun createSocket(host: String, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        open(InetSocketAddress(host, port), InetSocketAddress(localAddress, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        open(InetSocketAddress(host, port), null)

    override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        open(InetSocketAddress(host, port), InetSocketAddress(localAddress, localPort))

    private fun open(remote: InetSocketAddress, local: InetSocketAddress?): Socket {
        val socket = SplittingSocket(splitAt)
        if (local != null) socket.bind(local)
        socket.connect(remote)
        return socket
    }

    companion object {
        /** One byte is enough; the rest of the handshake lands in the following segment. */
        const val DEFAULT_SPLIT_AT = 1
    }
}

private class SplittingSocket(private val splitAt: Int) : Socket() {

    private var stream: OutputStream? = null

    override fun getOutputStream(): OutputStream {
        stream?.let { return it }
        // Without this Nagle holds the tail back until the leading byte is acked.
        tcpNoDelay = true
        return SplittingOutputStream(super.getOutputStream(), splitAt).also { stream = it }
    }
}

/**
 * Splits the first write into two, then gets out of the way. Only the ClientHello — or the
 * request line on a plaintext connection — is ever inspected by the filters this works around.
 */
internal class SplittingOutputStream(
    private val delegate: OutputStream,
    private val splitAt: Int,
) : OutputStream() {

    private var written = false

    override fun write(b: Int) {
        written = true
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val split = !written && len > splitAt
        written = true
        if (!split) {
            delegate.write(b, off, len)
            return
        }
        delegate.write(b, off, splitAt)
        delegate.flush()
        delegate.write(b, off + splitAt, len - splitAt)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
