package mihon.feature.translation.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Google Translate's public web endpoint. No key, no quota to manage — the fallback that keeps the
 * feature usable before the user has set anything up.
 *
 * It is a sentence-level machine translator: fine for speech bubbles, weak for narrative prose. The
 * novel pipeline therefore warns the user rather than silently producing a stilted chapter.
 */
class GoogleTranslationProvider : TranslationProvider {

    override val displayName = "Google Dịch"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translateLines(
        texts: List<String>,
        context: TranslationContext,
    ): List<BubbleTranslation> = withIOContext {
        // One request per string, so the source is known exactly and the mapping cannot drift.
        //
        // Those requests run concurrently. Issued one after another they were the single slowest
        // thing in the pipeline: a page of twenty bubbles meant twenty round trips end to end, and
        // the endpoint answers in well under a second — nearly all of that time was waiting. A small
        // window keeps the ordering guarantee (results are indexed, not appended) without hammering
        // an endpoint that has no published rate limit and is best not tested for one.
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        coroutineScope {
            texts.map { text ->
                async {
                    if (text.isBlank()) {
                        BubbleTranslation(text, "")
                    } else {
                        semaphore.withPermit {
                            val cleaned = MangaOcrCleaner.clean(text)
                            BubbleTranslation(text, translateOne(cleaned.replace('\n', ' '), context))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun translateProse(
        paragraphs: List<String>,
        context: TranslationContext,
        prose: ProseContext,
    ): ProseTranslation = withIOContext {
        // Translate paragraph by paragraph: the endpoint truncates long bodies, and keeping the
        // one-in-one-out mapping is what lets the reader preserve scroll position.
        val translated = paragraphs.map { paragraph ->
            if (paragraph.isBlank()) "" else translateOne(paragraph, context)
        }
        ProseTranslation(translated.joinToString("\n\n"))
    }

    private fun translateOne(text: String, context: TranslationContext): String {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", context.sourceLanguage)
            .addQueryParameter("tl", context.targetLanguage)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) return text

                // Shape: [[["translated","original",...], ...], ...]
                val sentences = json.parseToJsonElement(body).jsonArray.firstOrNull() as? JsonArray
                    ?: return text
                val builder = StringBuilder()
                for (sentence in sentences) {
                    val parts = sentence as? JsonArray ?: continue
                    builder.append(parts.firstOrNull()?.asText().orEmpty())
                }
                builder.toString().ifBlank { text }
            }
        } catch (e: Throwable) {
            logcat { "Google Translate failed for '${text.take(40)}': ${e.message}" }
            text
        }
    }

    private companion object {
        /** Concurrent lookups. Enough to hide latency, few enough to stay a polite client. */
        const val MAX_CONCURRENT_REQUESTS = 6
        const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }
}
