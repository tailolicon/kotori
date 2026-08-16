package app.kotori.extension.vi.wattpad

import eu.kanade.tachiyomi.source.novel.NovelChapterHtml
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wattpad's own markup for an inline picture, captured from `apiv2/storytext`.
 *
 * The reader shows a "picture failed" placeholder when a download fails, so a chapter that renders
 * *nothing at all* means the illustration never survived extraction. This pins the shape Wattpad
 * actually sends — an `<img>` inside a `<p data-media-type="image">` — against the walk that has to
 * find it.
 */
class WattpadImageTest {

    private val paragraph = """<p data-media-type="image" data-image-layout="one-horizontal" data-p-id="f2d32f75413e003e8018b5bce78ecf5e"> <img data-original-width="700" data-original-height="1533" src="https://img.wattpad.com/69d4aaa10ac80b8cfcbaf5fd9febe211d97c8491/68747470733a2f2f73332e616d617a6f6e6177732e636f6d2f776174747061642d6d656469612d736572766963652f53746f7279496d6167652f43506b5a546c6b34747748574b773d3d2d3733343730323332332e3135613036633433333536323430616234323133323835323232322e6a7067"></p>"""

    @Test
    fun `an inline picture survives extraction`() {
        val body = Jsoup.parseBodyFragment(paragraph, "https://www.wattpad.com").body()
        NovelChapterHtml.stripHiddenContent(body)

        val blocks = NovelChapterHtml.toBlocks(body)
        val images = blocks.filter { it.startsWith(NovelChapterHtml.IMAGE_SENTINEL) }

        assertEquals(1, images.size, "expected exactly one illustration, got blocks: $blocks")
        assertTrue(
            images.single().removePrefix(NovelChapterHtml.IMAGE_SENTINEL)
                .startsWith("https://img.wattpad.com/"),
            "illustration should keep Wattpad's own cdn url",
        )
    }
}
