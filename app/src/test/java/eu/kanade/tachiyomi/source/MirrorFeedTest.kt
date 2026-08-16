package eu.kanade.tachiyomi.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The feed is a file on the internet read by app versions that shipped before it was written,
 * so the cases that matter are the malformed ones: every one of them has to degrade to "use the
 * list in the apk", never to a crash inside a source's first request.
 */
class MirrorFeedTest {

    private val feed = """
        {
          "version": 1,
          "sources": [
            {
              "key": "animehay",
              "match": ["animehay"],
              "marker": "the-loai/anime-1",
              "announce": [],
              "domains": ["animehay11.site", "animehay12.site"]
            },
            {
              "key": "animevietsub",
              "match": ["animevietsub"],
              "marker": "danh-sach/list-dang-chieu",
              "announce": ["https://bit.ly/animevietsubtv"],
              "domains": ["animevietsub.vc", "animevietsub.info"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `picks the entry matching the shipped hosts`() {
        val parsed = parseMirrorFeed(feed, listOf("animevietsub.mom", "animevietsub.meme"))

        assertEquals(listOf("animevietsub.vc", "animevietsub.info"), parsed?.domains)
        assertEquals(listOf("https://bit.ly/animevietsubtv"), parsed?.announce)
        assertEquals("danh-sach/list-dang-chieu", parsed?.marker)
    }

    /** The counter moves and the TLD moves; the name in the middle is what identifies the site. */
    @Test
    fun `matches on the name, not the exact host`() {
        val parsed = parseMirrorFeed(feed, listOf("animehay08.site"))

        assertEquals("animehay11.site", parsed?.domains?.first())
    }

    @Test
    fun `falls back to matching a shipped host when no token does`() {
        val untagged = feed.replace("\"match\": [\"animehay\"],", "")

        val parsed = parseMirrorFeed(untagged, listOf("animehay12.site"))

        assertEquals(listOf("animehay11.site", "animehay12.site"), parsed?.domains)
    }

    @Test
    fun `a source with no entry keeps its shipped list`() {
        assertNull(parseMirrorFeed(feed, listOf("animetvn.cx")))
    }

    @Test
    fun `unknown fields are ignored rather than fatal`() {
        val future = feed.replace("\"key\": \"animehay\",", "\"key\": \"animehay\", \"weight\": 3,")

        assertEquals("animehay11.site", parseMirrorFeed(future, listOf("animehay08.site"))?.domains?.first())
    }

    @Test
    fun `malformed input yields nothing`() {
        listOf("", "   ", "not json", "{}", """{"sources": {}}""", """{"sources": [3, null]}""")
            .forEach { assertNull(parseMirrorFeed(it, listOf("animehay08.site")), "parsed: $it") }
    }

    @Test
    fun `an entry with nothing usable is skipped`() {
        val empty = """{"sources":[{"match":["animehay"],"domains":[],"announce":[]}]}"""

        assertNull(parseMirrorFeed(empty, listOf("animehay08.site")))
    }

    @Test
    fun `blank and non-string domains are dropped`() {
        val messy = """{"sources":[{"match":["animehay"],"domains":["  animehay11.site ","",null]}]}"""

        assertEquals(listOf("animehay11.site"), parseMirrorFeed(messy, listOf("animehay08.site"))?.domains)
    }
}
