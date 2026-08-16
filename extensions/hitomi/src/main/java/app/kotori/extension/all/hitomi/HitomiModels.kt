package app.kotori.extension.all.hitomi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class HitomiGallery(
    val id: JsonElement? = null,
    val title: String? = null,
    val japanese_title: String? = null,
    val language: String? = null,
    val type: String? = null,
    val date: String? = null,
    val artists: List<HitomiNamed>? = null,
    val groups: List<HitomiNamed>? = null,
    val parodys: List<HitomiNamed>? = null,
    val characters: List<HitomiNamed>? = null,
    val tags: List<HitomiTag>? = null,
    val files: List<HitomiFile>? = null,
) {
    fun numericId(): Long = id?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: error("Gallery is missing an id")

    fun displayTags(): List<String> = tags.orEmpty().mapNotNull { tag ->
        val name = tag.tag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        when {
            tag.female.isTruthy() -> "female:$name"
            tag.male.isTruthy() -> "male:$name"
            else -> name
        }
    }
}

@Serializable
internal data class HitomiNamed(
    val artist: String? = null,
    val group: String? = null,
    val parody: String? = null,
    val character: String? = null,
)

@Serializable
internal data class HitomiTag(
    val tag: String? = null,
    val female: JsonElement? = null,
    val male: JsonElement? = null,
)

@Serializable
internal data class HitomiFile(
    val name: String? = null,
    val hash: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val haswebp: Int = 0,
    val hasavif: Int = 0,
)

private fun JsonElement?.isTruthy(): Boolean {
    val raw = this?.jsonPrimitive?.contentOrNull ?: return false
    return raw == "1" || raw.equals("true", ignoreCase = true)
}
