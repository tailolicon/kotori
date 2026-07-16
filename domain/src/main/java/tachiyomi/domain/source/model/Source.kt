package tachiyomi.domain.source.model

data class Source(
    val id: Long,
    val lang: String,
    val name: String,
    val supportsLatest: Boolean,
    val isStub: Boolean,
    val pin: Pins = Pins.unpinned,
    val isUsedLast: Boolean = false,
    /**
     * Whether this source serves prose rather than pages. Novels share the manga stack, so the
     * Manga and Novel tabs are told apart by this flag rather than by separate managers.
     */
    val isNovel: Boolean = false,
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
