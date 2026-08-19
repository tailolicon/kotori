package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JapaneseOcrCleanerTest {

    @Test
    fun `restores the missing dakuten on gozaimasu`() {
        assertEquals("おはようございます", JapaneseOcrCleaner.clean("おはようこざいます"))
        assertEquals("ございません", JapaneseOcrCleaner.clean("こざいません"))
    }

    @Test
    fun `repairs the recurring kokoro-as-ima slip`() {
        assertEquals("心配ないさ任せろ", JapaneseOcrCleaner.clean("今配ないさ任せろ"))
    }

    @Test
    fun `collapses spaces ML Kit inserts between CJK glyphs`() {
        assertEquals("今日はいい天気", JapaneseOcrCleaner.clean("今日は いい 天気"))
    }

    @Test
    fun `leaves non-Japanese text alone`() {
        assertEquals("HELLO THERE", JapaneseOcrCleaner.clean("HELLO THERE"))
        assertEquals("안녕하세요", JapaneseOcrCleaner.clean("안녕하세요"))
    }
}
