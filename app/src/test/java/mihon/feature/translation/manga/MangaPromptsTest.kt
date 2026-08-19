package mihon.feature.translation.manga

import mihon.feature.translation.provider.TranslationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaPromptsTest {

    @Test
    fun `batch prompt is the Manga-Translator spoken Vietnamese contract`() {
        val prompt = MangaPrompts.batchPrompt(
            listOf("幸人もう遅い"),
            TranslationContext("ja", "vi"),
        )
        assertTrue(prompt.contains("ĐÂY LÀ HỘI THOẠI NÓI"))
        assertTrue(prompt.contains("JSON array"))
        assertTrue(prompt.contains("幸人もう遅い"))
    }

    @Test
    fun `parses a fenced json array of the expected length`() {
        val parsed = MangaPrompts.parseBatch("""```json
["Yukito à, muộn rồi","Kết thúc hôm nay đi"]
```""", 2)
        assertEquals(listOf("Yukito à, muộn rồi", "Kết thúc hôm nay đi"), parsed)
    }

    @Test
    fun `rejects a length mismatch`() {
        assertNull(MangaPrompts.parseBatch("""["one"]""", 2))
    }
}
