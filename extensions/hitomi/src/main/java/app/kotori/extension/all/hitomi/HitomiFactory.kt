package app.kotori.extension.all.hitomi

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class HitomiFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Hitomi("all", "all"),
        Hitomi("en", "english"),
        Hitomi("ja", "japanese"),
        Hitomi("ko", "korean"),
        Hitomi("zh", "chinese"),
        Hitomi("vi", "vietnamese"),
        Hitomi("id", "indonesian"),
        Hitomi("ru", "russian"),
        Hitomi("es", "spanish"),
    )
}
