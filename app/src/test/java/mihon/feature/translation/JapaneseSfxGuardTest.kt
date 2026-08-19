package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JapaneseSfxGuardTest {

    @Test
    fun `repeated kana is a sound effect`() {
        assertTrue(JapaneseSfxGuard.shouldDrop("バアアア"))
        assertTrue(JapaneseSfxGuard.shouldDrop("ドオオオ"))
        assertTrue(JapaneseSfxGuard.shouldDrop("ッ"))
    }

    @Test
    fun `ordinary dialogue stays`() {
        assertFalse(JapaneseSfxGuard.shouldDrop("ダメだ"))
        assertFalse(JapaneseSfxGuard.shouldDrop("心配ないさ"))
        assertFalse(JapaneseSfxGuard.shouldDrop("逃げろ!"))
        assertFalse(JapaneseSfxGuard.shouldDrop("あああ、そうなのか"))
        assertFalse(JapaneseSfxGuard.shouldDrop("えーーー何それ"))
        assertFalse(JapaneseSfxGuard.shouldDrop("うわあああ待って"))
    }
}
