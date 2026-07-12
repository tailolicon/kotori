package eu.kanade.tachiyomi.animesource.model

/**
 * API-surface stand-in for Aniyomi's NanoHTTPD-backed local http server
 * (extensions-lib 16 torrent support). Kotori does not bundle a torrent
 * engine; extensions that require a real local server are unsupported.
 */
open class HttpServer {
    open val url: String
        get() = ""

    open fun isRunning(): Boolean = false

    open fun start() {}

    open fun stop() {}
}
