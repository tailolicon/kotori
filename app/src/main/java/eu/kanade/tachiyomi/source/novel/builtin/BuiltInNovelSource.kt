package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.source.online.NovelHttpSource

/**
 * Base for novel sources compiled into the app rather than installed as an extension.
 *
 * Mirrors the anime side's `BuiltInHttpSource`: the only thing added over [NovelHttpSource] is
 * [iconUrl], since a built-in source has no extension package for the app to pull an icon from.
 */
abstract class BuiltInNovelSource : NovelHttpSource() {

    override val lang: String = "vi"

    /** Remote icon shown in Browse, standing in for the missing extension package icon. */
    abstract val iconUrl: String

    companion object {
        const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
