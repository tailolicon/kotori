package mihon.feature.translation.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.common.util.system.logcat

/**
 * Parsing for Gemini's `generateContent` envelope.
 *
 * Even with `responseMimeType: application/json` set, models occasionally wrap the payload in a
 * markdown fence or emit bare lines, so every parse has a text fallback. Returning a short list is
 * always safer than throwing: callers pad it and simply leave those bubbles untranslated.
 */
internal object GeminiResponse {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun errorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * Parses the bubble reply into exactly [expected] slots.
     *
     * Placement is by the `id` the model echoes back, not by position. That distinction is the whole
     * point: with positional alignment a single merged or omitted bubble shifts every translation
     * after it by one, and the page renders with dialogue under the wrong speakers — which reads as a
     * mistranslation even though every individual line was translated correctly.
     *
     * Positional alignment survives as the fallback for replies that are still a bare array of
     * strings, since that is strictly better than discarding the reply.
     */
    fun parseBubbles(body: String, expected: Int): List<BubbleTranslation> {
        val blank = BubbleTranslation("", "")
        val text = extractText(body) ?: return List(expected) { blank }
        val cleaned = stripFence(text)

        val array = arrayIn(cleaned)
            ?: return align(parseStringArrayFromText(text).map { BubbleTranslation("", sanitizeTranslation(it)) }, expected)

        val objects = array.mapNotNull { it as? JsonObject }
        if (objects.isEmpty()) {
            return align(array.map { BubbleTranslation("", sanitizeTranslation(it.asText())) }, expected)
        }

        val slots = MutableList(expected) { blank }
        var placedById = 0
        val unkeyed = mutableListOf<BubbleTranslation>()

        for ((position, entry) in objects.withIndex()) {
            val translated = entry["translation"]?.asText()
                ?: entry["vi"]?.asText()
                ?: entry["target"]?.asText()
            // Only fall back to `text` when there is no translation field at all; when both exist
            // `text` is the source the model was asked to echo, never the answer.
            val result = BubbleTranslation(
                source = if (translated == null) "" else entry["text"]?.asText().orEmpty().trim(),
                translation = sanitizeTranslation(translated ?: entry["text"]?.asText().orEmpty()),
            )
            val id = entry["id"]?.asInt() ?: entry["index"]?.asInt()
            when {
                id != null && id - 1 in 0 until expected -> {
                    slots[id - 1] = result
                    placedById++
                }
                id == null -> unkeyed += result
                // An out-of-range id is a model error; drop it rather than guess a slot.
                else -> logcat { "Discarding bubble translation with out-of-range id $id (expected 1..$expected)" }
            }
            if (id == null && position == 0) logcat { "Bubble reply had no ids; falling back to order" }
        }

        // A reply that keyed nothing is an ordinary ordered array wearing objects.
        if (placedById == 0 && unkeyed.isNotEmpty()) return align(unkeyed, expected)

        if (objects.size != expected) {
            logcat { "Bubble reply had ${objects.size} entries for $expected bubbles; placed $placedById by id" }
        }
        return slots
    }

    private fun align(values: List<BubbleTranslation>, size: Int): List<BubbleTranslation> = when {
        values.size == size -> values
        values.size > size -> values.take(size)
        else -> values + List(size - values.size) { BubbleTranslation("", "") }
    }

    /**
     * The bubble array, whether it was sent bare or wrapped in an object.
     *
     * The wrapper is not hypothetical: OpenAI-compatible endpoints used with `response_format:
     * json_object` cannot return a top-level array at all, so those models are obliged to nest it
     * under a key of their choosing.
     */
    private fun arrayIn(cleaned: String): JsonArray? {
        val root = runCatching { json.parseToJsonElement(cleaned) }.getOrNull() ?: return null
        (root as? JsonArray)?.let { return it }
        val obj = root as? JsonObject ?: return null
        WRAPPER_KEYS.forEach { key -> (obj[key] as? JsonArray)?.let { return it } }
        // Unknown key, single array value: take it rather than lose the whole reply.
        return obj.values.filterIsInstance<JsonArray>().singleOrNull()
    }

    /**
     * Keeps only the translated half of a reply.
     *
     * Models asked to read and then translate will sometimes answer `Run!!" -> "Chạy đi!!` in one
     * string. The structured schema is what stops this happening; this is the net under it, because a
     * leak here is rendered straight onto the artwork where the reader sees it.
     */
    fun sanitizeTranslation(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""

        // `source" -> "translation`, in any of the arrow spellings models reach for. Split on the last
        // arrow so a translation that itself contains one is not truncated at the first.
        val arrow = QUOTED_ARROW.findAll(value).lastOrNull()
        if (arrow != null) value = value.substring(arrow.range.last + 1).trim()

        value = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        value = value.removePrefix("\"").removeSuffix("\"").trim()
        value = LEADING_INDEX.replace(value, "").trim()
        return value.replace(WHITESPACE_RUN, " ")
    }

    fun parseProse(body: String, expectedParagraphs: Int): ProseTranslation {
        val text = extractText(body) ?: return ProseTranslation("")
        val cleaned = stripFence(text)

        val root = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
        if (root != null) {
            val paragraphs = (root["paragraphs"] as? JsonArray)
                ?.map { it.asText() }
                ?: emptyList()
            val glossary = (root["glossary"] as? JsonArray)
                ?.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    val source = entry["source"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val target = entry["target"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    GlossaryEntry(source, target)
                }
                .orEmpty()

            if (paragraphs.isNotEmpty()) {
                val aligned = alignTo(paragraphs, expectedParagraphs)
                return ProseTranslation(aligned.joinToString("\n\n"), glossary)
            }
        }

        // No usable object: treat the whole reply as the translated body. Better a slightly
        // mis-segmented chapter than an error screen.
        logcat { "Prose response was not a JSON object; using raw text (${cleaned.length} chars)" }
        return ProseTranslation(cleaned)
    }

    private fun parseStringArrayFromText(text: String): List<String> {
        val cleaned = stripFence(text)

        runCatching { json.parseToJsonElement(cleaned).jsonArray }
            .getOrNull()
            ?.let { array -> return array.map { it.asText() } }

        // Some models answer with one translation per line instead of an array.
        val lines = cleaned.lineSequence()
            .map { it.trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() && it != "[" && it != "]" }
            .map { it.removeSurrounding("\"").removeSurrounding("'") }
            .toList()

        if (lines.isNotEmpty()) return lines

        logcat { "Could not parse translation payload: ${cleaned.take(200)}" }
        return emptyList()
    }

    fun candidateText(body: String): String? = extractText(body)

    private fun extractText(body: String): String? = runCatching {
        json.parseToJsonElement(body)
            .jsonObject["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.mapNotNull { part ->
                val obj = part.jsonObject
                if (isThoughtPart(obj)) null else obj["text"]?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun isThoughtPart(part: JsonObject): Boolean {
        val flag = part["thought"] as? JsonPrimitive ?: return false
        return flag.booleanOrNull == true || flag.content.equals("true", ignoreCase = true)
    }

    private fun stripFence(text: String): String = text.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    /** A quote butted against an arrow — the shape of a leaked `source" -> "translation` reply. */
    private val WRAPPER_KEYS = listOf("bubbles", "translations", "results", "items", "data")
    private val QUOTED_ARROW = Regex("""["'”』】]\s*(?:->|-->|=>|→|⇒)\s*["'“『【]""")
    private val LEADING_INDEX = Regex("""^\s*(?:\[\d+]|\d+[.)])\s*""")
    private val WHITESPACE_RUN = Regex("""\s+""")
}

internal fun kotlinx.serialization.json.JsonElement.asText(): String =
    (this as? JsonPrimitive)?.contentOrNull ?: ""

internal fun kotlinx.serialization.json.JsonElement.asInt(): Int? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
