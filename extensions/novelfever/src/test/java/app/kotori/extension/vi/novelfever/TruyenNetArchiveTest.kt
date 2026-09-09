package app.kotori.extension.vi.novelfever

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TruyenNetArchiveTest {

    @Test
    fun `search recognises both reported titles`() {
        assertTrue(TruyenNetArchive.matches("Chấp Ma"))
        assertTrue(TruyenNetArchive.matches("chap ma"))
        assertTrue(TruyenNetArchive.matches("Hợp Thể Song Tu"))
        assertFalse(TruyenNetArchive.matches("ma"))
        assertFalse(TruyenNetArchive.matches("Đấu La Đại Lục"))
    }

    @Test
    fun `details retain the tagged url and expose pagination`() {
        val previous = SManga.create().apply {
            url = TruyenNetArchive.NOVEL_URL
            title = "Chấp Ma"
        }
        val html = """
            <html><body>
              <h1 itemprop="name">Chấp Ma</h1>
              <img itemprop="image" src="/media/book/chap-ma.jpg">
              <a itemprop="author">Ngã Thị Mặc Thủy</a>
              <div class="li--genres"><a>Tiên Hiệp</a></div>
              <span class="label-status">Đang cập nhật</span>
              <div itemprop="description">Ta chấp niệm là ánh sáng.</div>
              <input name="bid" value="81286">
              <div id="chapter-list">
                <div class="paging">
                  <a onclick="page(81286,2);">2</a>
                  <a onclick="page(81286,16);">Cuối</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val details = TruyenNetArchive.parseDetails(html, previous)

        assertEquals("81286", details.bookId)
        assertEquals(16, details.lastPage)
        assertEquals(TruyenNetArchive.NOVEL_URL, details.novel.url)
        assertEquals("Ngã Thị Mặc Thủy", details.novel.author)
        assertEquals(SManga.ONGOING, details.novel.status)
    }

    @Test
    fun `chapter fragments become tagged ordered chapters`() {
        val html = """
            <div class="clearfix"><ul>
              <li><a href="/chap-ma/chuong-101-abc">Chương 101: Bế quan</a></li>
              <li><a href="/chap-ma/chuong-102-def">Chương 102: Lột xác</a></li>
            </ul></div>
        """.trimIndent()

        val chapters = TruyenNetArchive.parseChapters(html)

        assertEquals(listOf(101f, 102f), chapters.map { it.chapter_number })
        assertEquals(
            "/external/truyennet/chap-ma/chuong-101-abc",
            chapters.first().url,
        )
    }

    @Test
    fun `chapter text drops duplicated site headings and keeps paragraphs`() {
        val html = """
            <html><body>
              <h2 class="current-chapter">Chương 1: Thái Cổ nhất mộng</h2>
              <div id="story">Chương 1: Thái Cổ nhất mộng<br><br>
                Chương 1: Thái Cổ nhất mộng<br><br>
                Thờì gian đổi mới 2013-8-77: 29: 44 số lượng từ: 3022<br><br>
                Đoạn một.<br><br>Đoạn hai.
              </div>
            </body></html>
        """.trimIndent()

        assertEquals("Đoạn một.\n\nĐoạn hai.", TruyenNetArchive.parseChapterText(html))
    }
}
