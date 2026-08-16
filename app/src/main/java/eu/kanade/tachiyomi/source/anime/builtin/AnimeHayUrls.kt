package eu.kanade.tachiyomi.source.anime.builtin

/**
 * AnimeHay listing URLs.
 *
 * Genre slugs carry a numeric id (`hanh-dong-2`, `anime-1`). Page 2 is
 * `/the-loai/{full-slug}/trang-2.html`. Stripping the id produced `/the-loai/hanh-dong/trang-2.html`,
 * which is a different (or empty) listing — the first page of a genre looked right and every
 * later page was someone else's catalogue.
 */
internal object AnimeHayUrls {

    fun genre(baseUrl: String, slug: String, page: Int): String {
        val root = baseUrl.trimEnd('/')
        return if (page <= 1) {
            "$root/the-loai/$slug.html"
        } else {
            "$root/the-loai/$slug/trang-$page.html"
        }
    }
}
