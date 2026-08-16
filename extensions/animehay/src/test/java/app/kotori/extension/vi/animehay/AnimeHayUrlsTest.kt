package app.kotori.extension.vi.animehay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnimeHayUrlsTest {

    @Test
    fun `page 1 keeps the numeric id on the slug`() {
        assertEquals(
            "https://animehay15.site/the-loai/hanh-dong-2.html",
            AnimeHayUrls.genre("https://animehay15.site", "hanh-dong-2", 1),
        )
    }

    @Test
    fun `later pages keep the full slug instead of stripping the id`() {
        assertEquals(
            "https://animehay15.site/the-loai/hanh-dong-2/trang-2.html",
            AnimeHayUrls.genre("https://animehay15.site", "hanh-dong-2", 2),
        )
        assertEquals(
            "https://animehay15.site/the-loai/anime-1/trang-3.html",
            AnimeHayUrls.genre("https://animehay15.site", "anime-1", 3),
        )
    }
}
