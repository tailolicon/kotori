package eu.kanade.tachiyomi.source.anime.builtin.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import okhttp3.Request as OkHttpRequest

/**
 * Bridges NewPipeExtractor's [Downloader] onto the app's shared OkHttp client so the
 * YouTube built-in sources reuse the app networking stack (cache, DoH, Cloudflare, etc.).
 */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = OkHttpRequest.Builder().url(request.url())

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        if (request.headers().keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", USER_AGENT)
        }

        val data = request.dataToSend()
        val body = if (data != null) data.toRequestBody(null, 0, data.size) else null
        builder.method(request.httpMethod(), body)

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
