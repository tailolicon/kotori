package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptKindDetectorTest {

    @Test
    fun `classifies the four scripts a mixed page can carry`() {
        assertEquals(ScriptKind.JAPANESE, ScriptKindDetector.of("心配ないさ任せろ"))
        assertEquals(ScriptKind.KOREAN, ScriptKindDetector.of("오늘 저녁에 볼래"))
        assertEquals(ScriptKind.CHINESE, ScriptKindDetector.of("今天天气真好"))
        assertEquals(ScriptKind.LATIN, ScriptKindDetector.of("¿Qué haces aquí?"))
        assertEquals(ScriptKind.NONE, ScriptKindDetector.of("!!!"))
    }

    @Test
    fun `korean recogniser junk on spanish looks like the wrong script`() {
        assertTrue(ScriptKindDetector.looksLikeJunk("0}2| 0.", ScriptKind.KOREAN))
        assertFalse(ScriptKindDetector.looksLikeJunk("오늘 저녁에 볼래", ScriptKind.KOREAN))
        assertTrue(ScriptKindDetector.looksLikeJunk("Hola, ¿qué tal?", ScriptKind.KOREAN))
    }

    @Test
    fun `language codes match the on-device recognisers`() {
        assertEquals("ja", ScriptKindDetector.languageCode(ScriptKind.JAPANESE))
        assertEquals("ko", ScriptKindDetector.languageCode(ScriptKind.KOREAN))
        assertEquals("en", ScriptKindDetector.languageCode(ScriptKind.LATIN))
        assertEquals(ScriptKind.LATIN, ScriptKindDetector.ofLanguage("es"))
    }

    @Test
    fun `korean and latin are different writing systems`() {
        assertFalse(
            ScriptKindDetector.sameWritingSystem(ScriptKind.KOREAN, ScriptKind.LATIN),
        )
        assertTrue(
            ScriptKindDetector.sameWritingSystem(ScriptKind.JAPANESE, ScriptKind.CHINESE),
        )
        assertTrue(
            ScriptKindDetector.sameWritingSystem(ScriptKind.KOREAN, ScriptKind.NONE),
        )
    }

    @Test
    fun `all-kanji Japanese is not junk for a Japanese page`() {
        assertFalse(ScriptKindDetector.looksLikeJunk("大丈夫", ScriptKind.JAPANESE))
        assertFalse(ScriptKindDetector.looksLikeJunk("了解", ScriptKind.JAPANESE))
        assertFalse(ScriptKindDetector.looksLikeJunk("え", ScriptKind.JAPANESE))
        assertTrue(ScriptKindDetector.looksLikeJunk("0}2| 0.", ScriptKind.JAPANESE))
    }
}
