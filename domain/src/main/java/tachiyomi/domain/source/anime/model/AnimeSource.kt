package tachiyomi.domain.source.anime.model

data class AnimeSource(
    val id: Long,
    val lang: String,
    val name: String,
    val supportsLatest: Boolean,
    val isStub: Boolean,
    /** Compiled into the app rather than installed as an extension APK. */
    val isBuiltIn: Boolean = false,
    val pin: Pins = Pins.unpinned,
    val isUsedLast: Boolean = false,
) {

    val visualName: String
        get() = when {
            lang.isEmpty() -> name
            else -> "$name (${lang.uppercase()})"
        }

    val key: () -> String = {
        when {
            isUsedLast -> "$id-lastused"
            else -> "$id"
        }
    }
}
