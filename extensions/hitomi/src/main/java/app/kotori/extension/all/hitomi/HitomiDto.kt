package app.kotori.extension.all.hitomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Same shapes as the upstream `@Serializable` DTOs, decoded by hand.
 *
 * Extension APKs load with a child-first classloader and `kotlinx-serialization`
 * is `compileOnly`, so a generated serializer resolves `GeneratedSerializer$-CC`
 * out of the extension dex and crashes the host as soon as a gallery is opened.
 * Only the decoding differs; every field and every formatting rule below matches
 * the upstream extension.
 */
class Gallery(
    val galleryurl: String,
    val title: String,
    val japaneseTitle: String?,
    val date: String,
    val type: String?,
    val language: String?,
    val tags: List<Tag>?,
    val artists: List<Artist>?,
    val groups: List<Group>?,
    val characters: List<Character>?,
    val parodys: List<Parody>?,
    val files: List<ImageFile>,
)

class ImageFile(
    val hash: String,
    private val name: String,
) {
    val isGif get() = name.endsWith(".gif") || name.endsWith(".webp")
}

class Tag(
    private val female: JsonPrimitive?,
    private val male: JsonPrimitive?,
    private val tag: String,
) {
    val formatted get() = if (female?.content == "1") {
        tag.toCamelCase() + " ♀"
    } else if (male?.content == "1") {
        tag.toCamelCase() + " ♂"
    } else {
        tag.toCamelCase()
    }
}

class Artist(
    private val artist: String,
) {
    val formatted get() = artist.toCamelCase()
}

class Group(
    private val group: String,
) {
    val formatted get() = group.toCamelCase()
}

class Character(
    private val character: String,
) {
    val formatted get() = character.toCamelCase()
}

class Parody(
    private val parody: String,
) {
    val formatted get() = parody.toCamelCase()
}

private fun String.toCamelCase(): String {
    val result = StringBuilder(length)
    var capitalize = true
    for (char in this) {
        result.append(
            if (capitalize) {
                char.uppercase()
            } else {
                char.lowercase()
            },
        )
        capitalize = char.isWhitespace()
    }
    return result.toString()
}

internal object GalleryParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(body: String): Gallery {
        val obj = json.parseToJsonElement(body).jsonObject()
        return Gallery(
            galleryurl = obj.string("galleryurl").orEmpty(),
            title = obj.string("title").orEmpty(),
            japaneseTitle = obj.string("japanese_title"),
            date = obj.string("date").orEmpty(),
            type = obj.string("type"),
            language = obj.string("language"),
            tags = obj.list("tags") { Tag(it.primitive("female"), it.primitive("male"), it.string("tag").orEmpty()) },
            artists = obj.list("artists") { Artist(it.string("artist").orEmpty()) },
            groups = obj.list("groups") { Group(it.string("group").orEmpty()) },
            characters = obj.list("characters") { Character(it.string("character").orEmpty()) },
            parodys = obj.list("parodys") { Parody(it.string("parody").orEmpty()) },
            files = obj.list("files") { ImageFile(it.string("hash").orEmpty(), it.string("name").orEmpty()) }
                ?: emptyList(),
        )
    }

    private fun JsonElement.jsonObject(): JsonObject =
        this as? JsonObject ?: error("Expected a gallery object")

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.string(key: String): String? =
        primitive(key)?.jsonPrimitive?.contentOrNull

    /** Absent or `null` stays null, so `?.joinToString()` keeps upstream's semantics. */
    private fun <T> JsonObject.list(key: String, map: (JsonObject) -> T): List<T>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { element -> (element as? JsonObject)?.let(map) }
    }
}
