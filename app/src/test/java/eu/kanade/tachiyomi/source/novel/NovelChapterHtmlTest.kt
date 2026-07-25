package eu.kanade.tachiyomi.source.novel

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelChapterHtmlTest {

    private fun blocks(html: String, base: String = "https://docln.net"): List<String> {
        val body = Jsoup.parseBodyFragment(html, base).body()
        NovelChapterHtml.stripHiddenContent(body)
        return NovelChapterHtml.toBlocks(body)
    }

    @Test
    fun `keeps illustrations in place between paragraphs`() {
        val result = blocks(
            """
            <p>Mở đầu chương.</p>
            <p><img src="https://i2.hako.vip/one.jpg"></p>
            <p>Đoạn tiếp theo.</p>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Mở đầu chương.",
                NovelChapterHtml.IMAGE_SENTINEL + "https://i2.hako.vip/one.jpg",
                "Đoạn tiếp theo.",
            ),
            result,
        )
    }

    @Test
    fun `prefers the lazy attribute over a placeholder src`() {
        val result = blocks(
            """<p><img src="data:image/gif;base64,R0lGOD" data-src="https://i.imgur.com/real.png"></p>""",
        )

        assertEquals(listOf(NovelChapterHtml.IMAGE_SENTINEL + "https://i.imgur.com/real.png"), result)
    }

    @Test
    fun `reads a srcset candidate list, taking the widest`() {
        val result = blocks(
            """<p><img srcset="https://cdn.example.org/small.jpg 400w, https://cdn.example.org/big.jpg 1600w"></p>""",
        )

        // An illustration fills the column, so the smallest candidate — which is the one a srcset
        // conventionally lists first — would be upscaled and look soft.
        assertEquals(listOf(NovelChapterHtml.IMAGE_SENTINEL + "https://cdn.example.org/big.jpg"), result)
    }

    @Test
    fun `resolves a protocol-relative source against the site`() {
        val result = blocks(
            """<p><img src="//img.wattpad.com/story.png"></p>""",
            base = "https://www.wattpad.com",
        )

        assertEquals(listOf(NovelChapterHtml.IMAGE_SENTINEL + "https://img.wattpad.com/story.png"), result)
    }

    @Test
    fun `still renders pictures when the markup uses divs instead of paragraphs`() {
        val result = blocks(
            """
            <div>Dòng đầu tiên.<br>Dòng thứ hai.</div>
            <div><img src="https://i.imgur.com/only.png"></div>
            """.trimIndent(),
        )

        assertEquals(NovelChapterHtml.IMAGE_SENTINEL + "https://i.imgur.com/only.png", result.last())
        assertEquals(listOf("Dòng đầu tiên.", "Dòng thứ hai."), result.dropLast(1))
    }

    @Test
    fun `drops hidden markup and untrusted picture sources`() {
        val result = blocks(
            """
            <p>Nội dung thật.</p>
            <p style="display: none">Quảng cáo ẩn.</p>
            <script>alert(1)</script>
            <p><img src="http://127.0.0.1/evil.png"></p>
            """.trimIndent(),
        )

        assertEquals(listOf("Nội dung thật."), result)
    }

    @Test
    fun `strips the sentinel out of prose so it can never be mistaken for a picture`() {
        val result = blocks("<p>${NovelChapterHtml.IMAGE_SENTINEL}https://evil.example/x.png</p>")

        assertEquals(listOf("https://evil.example/x.png"), result)
    }
}
