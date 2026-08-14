package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisionFallbackSelectorTest {

    @Test
    fun `meaningful OCR dialogue omitted by vision is recovered`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("", "Đã dịch", ""),
            ocrTexts = listOf("THAT GUY NEVER CATCHES A BREAK", "OTHER LINE", "WHOA"),
        )

        assertEquals(listOf(0), indices)
    }

    @Test
    fun `short manga sound effects are not promoted into dialogue`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("", "", ""),
            ocrTexts = listOf("BAM", "WHOOSH", "..."),
        )

        assertEquals(emptyList<Int>(), indices)
    }

    @Test
    fun `existing vision translation is never overwritten`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("Bản dịch"),
            ocrTexts = listOf("THAT GUY NEVER CATCHES A BREAK"),
        )

        assertEquals(emptyList<Int>(), indices)
    }

    @Test
    fun `vision echoing an English bubble is recovered as untranslated`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("MY STANDING IS SO LOW!"),
            ocrTexts = listOf("My standing is so low!"),
        )

        assertEquals(listOf(0), indices)
    }

    @Test
    fun `short text inside confirmed speech bubble is recovered`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("YES.", "", ""),
            ocrTexts = listOf("YES.", "ROGER!", "BAM"),
            speechBoxes = listOf(true, true, false),
        )

        assertEquals(listOf(0, 1), indices)
    }

    @Test
    fun `near echo with minor OCR error is recovered`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("I'LL HAVE EVERYONE UNDERSTAND THAT"),
            ocrTexts = listOf("I'LL HAV EVERYON UNDERSTAND THAT"),
            speechBoxes = listOf(true),
        )

        assertEquals(listOf(0), indices)
    }

    @Test
    fun `provider source recovers handwritten speech when local OCR is blank`() {
        val indices = VisionFallbackSelector.missingIndices(
            translations = listOf("YES.", "IS ME...", "BAM"),
            ocrTexts = listOf("", "", ""),
            speechBoxes = listOf(true, true, false),
            providerSources = listOf("YES.", "IS ME...", "BAM"),
        )

        assertEquals(listOf(0, 1), indices)
    }
}
