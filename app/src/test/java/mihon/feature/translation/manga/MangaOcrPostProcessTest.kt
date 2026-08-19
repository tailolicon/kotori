package mihon.feature.translation.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaOcrPostProcessTest {

    @Test
    fun `matches manga-ocr post_process on known strings`() {
        assertEquals("幸人もう遅い．．．", MangaOcrPostProcess.apply("幸人 もう遅い…"))
        assertEquals("Ａ１", MangaOcrPostProcess.apply("A1"))
        assertEquals("あ．．い", MangaOcrPostProcess.apply("あ・・い"))
        assertEquals("ガギ", MangaOcrPostProcess.apply("ｶﾞｷﾞ"))
        assertEquals("ｈｅｌｌｏ．．．世界", MangaOcrPostProcess.apply("hello... 世界"))
    }

    @Test
    fun `h2z converts halfwidth ascii kana and digits`() {
        assertEquals("Ａ１ア", MangaOcrPostProcess.h2z("A1ｱ"))
    }

    @Test
    fun `japanese detector ignores latin hangul and a stray kanji`() {
        assertTrue(MangaOcrPostProcess.looksJapanese("幸人もう遅い"))
        assertTrue(MangaOcrPostProcess.looksJapanese("大丈夫です"))
        assertFalse(MangaOcrPostProcess.looksJapanese("HELLO"))
        assertFalse(MangaOcrPostProcess.looksJapanese("안녕하세요"))
        assertFalse(MangaOcrPostProcess.looksJapanese("漢"))
    }
}
