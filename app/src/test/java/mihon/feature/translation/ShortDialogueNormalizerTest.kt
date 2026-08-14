package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShortDialogueNormalizerTest {

    @Test
    fun `one-character OCR slip in short command is repaired`() {
        assertEquals("ROGER!", ShortDialogueNormalizer.normalize("ROSER!"))
        assertEquals("YES.", ShortDialogueNormalizer.normalize("YFS."))
        assertEquals("CLAIRVOYANT!?", ShortDialogueNormalizer.normalize("CLAR voYANT!?"))
    }

    @Test
    fun `names and long prose are not rewritten`() {
        assertEquals("MATO", ShortDialogueNormalizer.normalize("MATO"))
        assertEquals("THE HOUSE IS HERE", ShortDialogueNormalizer.normalize("THE HOUSE IS HERE"))
    }

    @Test
    fun `short dialogue can be distinguished from decorative lettering`() {
        assertTrue(ShortDialogueNormalizer.isLikelyUtterance("WAIT, NO!!"))
        assertTrue(ShortDialogueNormalizer.isLikelyUtterance("IS ME..."))
        assertFalse(ShortDialogueNormalizer.isLikelyUtterance("MHM~~"))
        assertFalse(ShortDialogueNormalizer.isLikelyUtterance("SHUSHU"))
        assertTrue(ShortDialogueNormalizer.isLikelyUtterance("I..."))
        assertFalse(ShortDialogueNormalizer.isLikelyUtterance("I"))
    }

    @Test
    fun `Google-preserved commands receive a natural Vietnamese translation`() {
        assertEquals("ĐÃ RÕ!", ShortDialogueNormalizer.directTranslation("ROSER!", "en", "vi"))
        assertEquals("KHOAN, KHÔNG!!", ShortDialogueNormalizer.directTranslation("WAIT, NO!!", "en", "vi"))
        assertEquals("ỪM...", ShortDialogueNormalizer.directTranslation("UMMM...", "en", "vi"))
        assertEquals("NHÀ NGOẠI CẢM!?", ShortDialogueNormalizer.directTranslation("CLAR voYANT!?", "en", "vi"))
        assertEquals("GỪỪ!!!", ShortDialogueNormalizer.directTranslation("RAWR!!!", "en", "vi"))
        assertEquals("NGAY BÂY GIỜ..", ShortDialogueNormalizer.directTranslation("NOW..", "en", "vi"))
        assertEquals("VÀ CŨNG", ShortDialogueNormalizer.directTranslation("AND AlsO", "en", "vi"))
        assertEquals("TÔI...", ShortDialogueNormalizer.directTranslation("I...", "en", "vi"))
        assertEquals(null, ShortDialogueNormalizer.directTranslation("MATO", "en", "vi"))
        assertEquals(null, ShortDialogueNormalizer.directTranslation("YES.", "ja", "vi"))
    }
}
