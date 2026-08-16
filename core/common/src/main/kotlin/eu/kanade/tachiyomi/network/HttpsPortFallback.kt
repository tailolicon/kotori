package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Some ISPs reset TLS on :443 when they see a blocked SNI, but leave Cloudflare's
 * other HTTPS ports alone. Retry those ports so sources like manga18fx can load.
 */
object HttpsPortFallback {
    val ports: List<Int> = listOf(8443, 2053, 2083, 2087, 2096)

    fun shouldRetry(url: HttpUrl, error: IOException): Boolean {
        if (!url.isHttps || url.port != 443) return false
        return isConnectionReset(error)
    }

    fun rewritePort(url: HttpUrl, port: Int): HttpUrl = url.newBuilder().port(port).build()

    fun isConnectionReset(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("Connection reset", ignoreCase = true) ||
                message.contains("ECONNRESET", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

class HttpsPortFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!HttpsPortFallback.shouldRetry(request.url, e)) throw e
            retry(chain, request, e)
        }
    }

    private fun retry(chain: Interceptor.Chain, request: Request, firstError: IOException): Response {
        var lastError: IOException = firstError
        var lastResponse: Response? = null
        for (port in HttpsPortFallback.ports) {
            val retried = request.newBuilder()
                .url(HttpsPortFallback.rewritePort(request.url, port))
                .build()
            try {
                lastResponse?.close()
                val response = chain.proceed(retried)
                if (response.code != 521) {
                    return response
                }
                lastResponse = response
            } catch (retryError: IOException) {
                lastError = retryError
            }
        }
        return lastResponse ?: throw lastError
    }
}
