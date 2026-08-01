package mihon.feature.translation.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Groq's OpenAI-compatible chat endpoint. Text only — bubble geometry still comes from the
 * on-device detector and the strings from ML Kit OCR.
 *
 * Chosen for latency: on a long chapter Groq returns a batch several times faster than a comparable
 * hosted model, which matters when the reader is waiting at a chapter boundary.
 */
class GroqTranslationProvider(
    private val apiKey: () -> String,
    private val model: () -> String,
) : TranslationProvider {

    override val displayName = "Groq"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translateLines(
        texts: List<String>,
        context: TranslationContext,
    ): List<BubbleTranslation> = withIOContext {
        if (texts.isEmpty()) return@withIOContext emptyList()
        val reply = chat(
            system = "You translate comics dialogue. Reply with JSON only.",
            user = TranslationPrompts.bubbleLines(context, texts),
            temperature = 0.2,
        )
        GeminiResponse.parseBubbles(wrapAsGeminiEnvelope(reply), texts.size)
    }

    override suspend fun translateProse(
        paragraphs: List<String>,
        context: TranslationContext,
        prose: ProseContext,
    ): ProseTranslation = withIOContext {
        if (paragraphs.isEmpty()) return@withIOContext ProseTranslation("")
        val reply = chat(
            system = "You are a literary translator. Reply with JSON only.",
            user = TranslationPrompts.prose(context, prose, paragraphs),
            temperature = 0.6,
        )
        GeminiResponse.parseProse(wrapAsGeminiEnvelope(reply), paragraphs.size)
    }

    private fun chat(system: String, user: String, temperature: Double): String {
        val key = apiKey()
        require(key.isNotBlank()) { "Chưa có Groq API key. Nhập trong Cài đặt → Dịch." }

        val payload = buildJsonObject {
            put("model", model())
            put("temperature", temperature)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", system)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", user)
                    },
                )
            }
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "Groq trả về lỗi ${response.code}"
                logcat { "Groq error ${response.code}: ${body.take(400)}" }
                if (response.code == 429) {
                    val retryAfter = response.header("retry-after")?.toDoubleOrNull()?.toLong()
                    throw ProviderRateLimited(
                        "Groq đang giới hạn tốc độ, tạm nghỉ một chút rồi dịch tiếp.",
                        retryAfterSeconds = retryAfter,
                        dailyQuota = "per day" in body.lowercase() || "daily" in body.lowercase(),
                    )
                }
                error(message)
            }

            return runCatching {
                json.parseToJsonElement(body)
                    .jsonObject["choices"]!!.jsonArray.first()
                    .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            }.getOrElse {
                logcat { "Unexpected Groq envelope: ${body.take(300)}" }
                ""
            }
        }
    }

    /**
     * Re-uses the Gemini payload parsers, which already handle markdown fences, bare-line replies and
     * `{"paragraphs": [...]}` objects. Wrapping is cheaper than duplicating that tolerance.
     */
    private fun wrapAsGeminiEnvelope(content: String): String = buildJsonObject {
        putJsonArray("candidates") {
            add(
                buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", content) })
                        }
                    }
                },
            )
        }
    }.toString()

    private companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
