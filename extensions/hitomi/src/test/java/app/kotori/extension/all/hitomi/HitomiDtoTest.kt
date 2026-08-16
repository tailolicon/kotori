package app.kotori.extension.all.hitomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hand-rolled parser has to hand back exactly what the upstream `@Serializable`
 * DTOs would: `null` for absent/null arrays (so `?.joinToString()` yields null),
 * empty strings for empty arrays, and the same Title Case + ♀/♂ formatting.
 */
class HitomiDtoTest {

    private fun parse(json: String) = GalleryParser.parse(json)

    @Test
    fun `null arrays stay null so joinToString falls through`() {
        val gallery = parse(
            """
            {
              "galleryurl": "/doujinshi/foo-4122354.html",
              "title": "foo",
              "date": "2026-08-15 21:24:00-05",
              "language": "chinese",
              "type": "doujinshi",
              "artists": null,
              "groups": null,
              "tags": [],
              "files": [{"hash": "abc123", "name": "001.jpg", "hasavif": 1}]
            }
            """.trimIndent(),
        )

        assertNull(gallery.artists)
        assertNull(gallery.groups)
        assertNull(gallery.characters)
        assertNull(gallery.parodys)
        assertEquals("", gallery.tags?.joinToString { it.formatted })
        assertEquals("doujinshi", gallery.type)
        assertEquals("/doujinshi/foo-4122354.html", gallery.galleryurl)
    }

    @Test
    fun `japanese_title is read from the snake_case key hitomi actually sends`() {
        val gallery = parse(
            """{"title":"foo","japanese_title":"ふー","date":"","files":[]}""",
        )

        assertEquals("ふー", gallery.japaneseTitle)
    }

    @Test
    fun `tags carry the gender suffix and Title Case`() {
        val gallery = parse(
            """
            {
              "title": "foo",
              "date": "",
              "tags": [
                {"tag": "big breasts", "female": "1"},
                {"tag": "yaoi", "male": "1"},
                {"tag": "full color"}
              ],
              "files": []
            }
            """.trimIndent(),
        )

        assertEquals(
            "Big Breasts ♀, Yaoi ♂, Full Color",
            gallery.tags?.joinToString { it.formatted },
        )
    }

    @Test
    fun `gif and webp pages fall back to the webp cdn`() {
        val gallery = parse(
            """
            {
              "title": "foo",
              "date": "",
              "files": [
                {"hash": "a", "name": "001.jpg"},
                {"hash": "b", "name": "002.gif"},
                {"hash": "c", "name": "003.webp"}
              ]
            }
            """.trimIndent(),
        )

        assertFalse(gallery.files[0].isGif)
        assertTrue(gallery.files[1].isGif)
        assertTrue(gallery.files[2].isGif)
    }

    @Test
    fun `artists and groups keep their formatting`() {
        val gallery = parse(
            """
            {
              "title": "foo",
              "date": "",
              "artists": [{"artist": "some artist"}],
              "groups": [{"group": "some circle"}],
              "characters": [{"character": "hatsune miku"}],
              "parodys": [{"parody": "vocaloid"}],
              "files": []
            }
            """.trimIndent(),
        )

        assertEquals("Some Artist", gallery.artists?.joinToString { it.formatted })
        assertEquals("Some Circle", gallery.groups?.joinToString { it.formatted })
        assertEquals("Hatsune Miku", gallery.characters?.joinToString { it.formatted })
        assertEquals("Vocaloid", gallery.parodys?.joinToString { it.formatted })
    }
}
