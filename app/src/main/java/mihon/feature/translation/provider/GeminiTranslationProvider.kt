package mihon.feature.translation.provider

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mihon.feature.translation.model.BubbleBox
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Google Gemini, used both as a vision reader (OCR + translation in a single call) and as the prose
 * translator for light novels.
 */
class GeminiTranslationProvider(
    private val apiKey: () -> String,
    private val model: () -> String,
) : TranslationProvider {

    override val displayName = "Gemini"

    /**
     * Gemma is served through this same endpoint but reads no images, so asking it to would fail the
     * request outright. Falling back to the text path costs a little accuracy on stylised lettering —
     * on-device OCR reads the page instead of the model — and buys an enormous amount of headroom:
     * Gemma allows 14,400 requests a day against Flash's 20, and sending text rather than a JPEG of
     * a webtoon strip is what keeps a chapter inside the token-per-minute limit as well.
     */
    override val supportsVisionOcr: Boolean
        get() = !model().startsWith("gemma", ignoreCase = true)

    private val keyRing = GeminiKeyRing()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun ocrAndTranslate(
        bitmap: Bitmap,
        boxes: List<BubbleBox>,
        context: TranslationContext,
    ): List<BubbleTranslation> = withIOContext {
        if (boxes.isEmpty()) return@withIOContext emptyList()

        val scaled = downscaleForApi(bitmap)
        val scaleX = scaled.width.toFloat() / bitmap.width
        val scaleY = scaled.height.toFloat() / bitmap.height

        // The bubbles are stamped with numbered badges before the page is sent. Coordinates alone do
        // not anchor ids reliably on a webtoon strip thousands of pixels tall — the model matches "the
        // 4th box in the list" to "the 4th bubble I noticed", and one slip letters every following
        // translation into the wrong mouth. A number drawn on the artwork is unambiguous.
        val stamped = withBadges(scaled, boxes, scaleX, scaleY)
        if (scaled !== bitmap && scaled !== stamped) scaled.recycle()

        val encoded = ByteArrayOutputStream().use { out ->
            stamped.compress(Bitmap.CompressFormat.JPEG, 78, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        val scaledWidth = stamped.width
        val scaledHeight = stamped.height
        if (stamped !== bitmap) stamped.recycle()

        val geometry = boxes.withIndex().joinToString("\n") { (index, box) ->
            "${index + 1}. (x1=${(box.left * scaleX).toInt()}, y1=${(box.top * scaleY).toInt()}, " +
                "x2=${(box.right * scaleX).toInt()}, y2=${(box.bottom * scaleY).toInt()})"
        }

        val payload = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(
                                buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", "image/jpeg")
                                        put("data", encoded)
                                    }
                                },
                            )
                            add(
                                buildJsonObject {
                                    put(
                                        "text",
                                        TranslationPrompts.visionBubbles(
                                            context,
                                            scaledWidth,
                                            scaledHeight,
                                            geometry,
                                            boxes.size,
                                        ),
                                    )
                                },
                            )
                        }
                    },
                )
            }
            put("generationConfig", generationConfig(jsonOutput = true, temperature = 0.15, thinkingBudget = 1024))
        }

        GeminiResponse.parseBubbles(call(payload), boxes.size)
    }

    /**
     * Manga-Translator Gemini batch: JSON array of bubble strings, spoken-Vietnamese prompt.
     * Returns null when the model does not give a same-length array so the caller can fall back.
     */
    suspend fun translateMangaBatch(
        texts: List<String>,
        context: TranslationContext,
    ): List<String>? = withIOContext {
        if (texts.isEmpty()) return@withIOContext emptyList()
        val indexed = texts.withIndex().filter { it.value.isNotBlank() }
        if (indexed.isEmpty()) return@withIOContext texts
        val toTranslate = indexed.map { it.value }
        val translated = translateMangaBatchInternal(toTranslate, context) ?: return@withIOContext null
        val result = texts.toMutableList()
        indexed.forEachIndexed { position, (orig, _) ->
            result[orig] = translated.getOrNull(position).orEmpty()
        }
        result
    }

    private fun translateMangaBatchInternal(texts: List<String>, context: TranslationContext): List<String>? {
        val payload = textPayload(
            prompt = mihon.feature.translation.manga.MangaPrompts.batchPrompt(texts, context),
            temperature = 0.2,
            thinkingBudget = 512,
        )
        repeat(3) { attempt ->
            try {
                val body = call(payload)
                val text = GeminiResponse.candidateText(body) ?: return@repeat
                val parsed = mihon.feature.translation.manga.MangaPrompts.parseBatch(text, texts.size)
                if (parsed != null) return parsed
                logcat { "Manga Gemini batch attempt ${attempt + 1} had wrong shape" }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: ProviderRateLimited) {
                throw error
            } catch (error: ProviderRejected) {
                throw error
            } catch (error: Exception) {
                logcat { "Manga Gemini batch attempt ${attempt + 1} failed: ${error.message}" }
            }
        }
        return null
    }

    override suspend fun translateLines(
        texts: List<String>,
        context: TranslationContext,
    ): List<BubbleTranslation> = withIOContext {
        if (texts.isEmpty()) return@withIOContext emptyList()
        val payload = textPayload(
            prompt = TranslationPrompts.bubbleLines(context, texts),
            temperature = 0.15,
            thinkingBudget = 1024,
        )
        GeminiResponse.parseBubbles(call(payload), texts.size)
    }

    override suspend fun translateProse(
        paragraphs: List<String>,
        context: TranslationContext,
        prose: ProseContext,
    ): ProseTranslation = withIOContext {
        if (paragraphs.isEmpty()) return@withIOContext ProseTranslation("")
        val payload = textPayload(
            prompt = TranslationPrompts.prose(context, prose, paragraphs),
            // Prose needs room to breathe: at low temperature and a clamped thinking budget the output
            // reads like a machine gloss rather than a translator's prose.
            temperature = 0.6,
            thinkingBudget = 4096,
        )
        GeminiResponse.parseProse(call(payload), paragraphs.size)
    }

    private fun textPayload(prompt: String, temperature: Double, thinkingBudget: Int): JsonObject =
        buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", prompt) })
                        }
                    },
                )
            }
            put("generationConfig", generationConfig(true, temperature, thinkingBudget))
        }

    private fun generationConfig(jsonOutput: Boolean, temperature: Double, thinkingBudget: Int): JsonObject {
        val modelName = model()
        return buildJsonObject {
            // Gemma-branded endpoints reject responseMimeType outright.
            if (jsonOutput && !modelName.startsWith("gemma")) {
                put("responseMimeType", "application/json")
            }
            put("temperature", temperature)
            if (modelName.contains("2.5") || modelName.startsWith("gemini-3")) {
                putJsonObject("thinkingConfig") { put("thinkingBudget", thinkingBudget) }
            }
        }
    }

    /**
     * Sends [payload], moving to the next API key rather than giving up when one is spent.
     *
     * A daily allowance is the thing that actually ends a reading session, and a reader with two
     * Google accounts has two allowances. So a quota or credentials refusal parks that key and the
     * request is tried again on the next one; only when every key has refused does the failure
     * reach the caller, which is when it genuinely means "stop translating for a while".
     */
    private fun call(payload: JsonObject): String {
        val keys = keyRing.parse(apiKey())
        require(keys.isNotEmpty()) { "Chưa có Gemini API key. Nhập trong Cài đặt → Dịch." }

        var lastFailure: Exception? = null
        for (key in keyRing.available(keys)) {
            try {
                val body = callWith(key, payload)
                keyRing.release(key)
                return body
            } catch (limited: ProviderRateLimited) {
                keyRing.park(
                    key,
                    when {
                        limited.dailyQuota -> GeminiKeyRing.DAILY_PARK_SECONDS
                        else -> limited.retryAfterSeconds ?: GeminiKeyRing.MINUTE_PARK_SECONDS
                    },
                )
                logcat { "Gemini key ...${key.takeLast(4)} is out of quota; trying the next one" }
                lastFailure = limited
            } catch (rejected: ProviderRejected) {
                keyRing.park(key, GeminiKeyRing.REJECTED_PARK_SECONDS)
                logcat { "Gemini refused key ...${key.takeLast(4)}; trying the next one" }
                lastFailure = rejected
            }
        }
        throw lastFailure ?: IllegalStateException("Gemini không trả lời")
    }

    private fun callWith(key: String, payload: JsonObject): String {
        val request = Request.Builder()
            .url("$ENDPOINT/${model()}:generateContent")
            .header("x-goog-api-key", key)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                logcat { "Gemini error ${response.code}: ${body.take(400)}" }
                if (response.code == 429) {
                    // The free tier's daily bucket and the per-minute bucket need opposite handling:
                    // one is over in seconds, the other only resets at midnight Pacific.
                    val daily = "PerDay" in body || "free_tier" in body
                    val retryAfter = RETRY_DELAY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
                    val message = if (daily) {
                        "Hết hạn mức Gemini miễn phí hôm nay trên mọi key đã nhập. Hạn mức tự đặt lại " +
                            "sau nửa đêm giờ Mỹ — hoặc thêm key khác / đổi model trong Cài đặt → Dịch."
                    } else {
                        "Gemini đang giới hạn tốc độ, tạm nghỉ một chút rồi dịch tiếp."
                    }
                    throw ProviderRateLimited(message, retryAfter, daily)
                }
                if (response.code == 403 || response.code == 401) {
                    // The key is not merely throttled — the project is blocked or the key is wrong.
                    // Retrying cannot fix it, and Google's own wording ("Lightning dunning decision
                    // is deny for project: projects/189621270308") tells the reader nothing.
                    throw ProviderRejected(
                        "Gemini từ chối API key (lỗi ${response.code}). Key có thể đã bị thu hồi, " +
                            "hoặc tài khoản Google đang vướng thanh toán. Kiểm tra lại key ở " +
                            "aistudio.google.com, hoặc tạm chuyển sang Google Dịch trong Cài đặt → Dịch.",
                    )
                }
                val message = GeminiResponse.errorMessage(body) ?: "Gemini trả về lỗi ${response.code}"
                error(message)
            }
            return body
        }
    }

    /**
     * Stamps each bubble's number onto the image, just above the box's top-left corner.
     *
     * Badge size scales with the page, and the badge is kept inside the canvas so a bubble flush with
     * the page edge still gets its number. The badge deliberately sits at the box *corner* rather
     * than its centre: covering dialogue with a badge would defeat the reading it exists to help.
     */
    private fun withBadges(
        scaled: Bitmap,
        boxes: List<BubbleBox>,
        scaleX: Float,
        scaleY: Float,
    ): Bitmap {
        val out = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: return scaled
        val canvas = android.graphics.Canvas(out)
        val textSize = (out.width / 42f).coerceIn(14f, 34f)
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            this.textSize = textSize
        }
        val badgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(220, 20, 20)
        }
        boxes.forEachIndexed { index, box ->
            val label = (index + 1).toString()
            val w = textPaint.measureText(label) + textSize * 0.6f
            val h = textSize * 1.35f
            val x = (box.left * scaleX).coerceIn(0f, out.width - w)
            val y = (box.top * scaleY - h * 0.4f).coerceIn(0f, out.height - h)
            canvas.drawRoundRect(x, y, x + w, y + h, h * 0.25f, h * 0.25f, badgePaint)
            canvas.drawText(label, x + textSize * 0.3f, y + h - textSize * 0.35f, textPaint)
        }
        return out
    }

    /**
     * Shrinks the page only as far as legibility allows.
     *
     * What matters to the model is how many pixels tall a line of lettering is, and that scales with
     * the page's *width*, not its longest side. Capping the long edge — the obvious thing to do —
     * silently destroys webtoons: an 800x15000 strip has its long edge cut to 1536, which leaves the
     * page 82 pixels wide. Nothing is readable at that size, so every bubble comes back empty and the
     * page looks to the reader like it simply has no dialogue.
     *
     * Width is therefore the cap, with a total-pixel ceiling as a second guard so a very long strip
     * still fits comfortably inside the request.
     */
    private fun downscaleForApi(bitmap: Bitmap): Bitmap {
        var scale = 1f
        if (bitmap.width > MAX_API_WIDTH) {
            scale = MAX_API_WIDTH.toFloat() / bitmap.width
        }

        val pixels = bitmap.width.toLong() * bitmap.height * scale * scale
        if (pixels > MAX_API_PIXELS) {
            scale *= sqrt(MAX_API_PIXELS.toDouble() / pixels).toFloat()
        }

        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        /** Matches the RetryInfo detail Gemini attaches to 429 bodies, e.g. `"retryDelay": "26s"`. */
        val RETRY_DELAY = Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"")
        /** Page width the model still reads comfortably. */
        const val MAX_API_WIDTH = 1536
        /** Second guard so a very long strip still fits inside one request. */
        const val MAX_API_PIXELS = 12_000_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Pads or trims [values] so callers can zip it against a fixed-size input list. */
internal fun alignTo(values: List<String>, size: Int): List<String> = when {
    values.size == size -> values
    values.size > size -> values.take(size)
    else -> values + List(size - values.size) { "" }
}
