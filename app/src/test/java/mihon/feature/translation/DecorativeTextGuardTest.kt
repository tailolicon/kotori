package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecorativeTextGuardTest {
    @Test
    fun `keeps large sentence-like manhwa captions`() {
        assertKept("YOU'VE ALSO DONE YOUR BEST TO FILL YOURSELF, MY LADY.")
        assertKept("BELLA... WHAT SHOULD I DO...")
        assertKept("I DON'T WANT TO LOSE TOMORROW...")
        assertKept("THEN WIN!")
    }

    @Test
    fun `drops large titles and one-word sound effects`() {
        assertDropped("CHAPTER 35")
        assertDropped("NOBLE LADY")
        assertDropped("THE END!")
        assertDropped("BOOM!")
        assertTrue(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "DOOMSDAY SWORD GOD",
                lineHeights = listOf(38),
                boxWidth = 578,
                boxHeight = 66,
                pageWidth = 1280,
            ),
        )
    }

    @Test
    fun `never drops real balloons or ordinary-sized captions`() {
        assertFalse(candidate(text = "CHAPTER 35", isTextBlock = false))
        assertFalse(candidate(text = "CHAPTER 35", lineHeights = listOf(24, 25)))
    }

    @Test
    fun `bold korean dialogue is kept`() {
        assertKept("\uD770\uC218\uC800 \uCC0C\uB530 \uC790\uC9C0\uB85C\n\uC77C\uC9C4\uB140 \uC544\uB2E4 \uBCF4\uC9C0\uC5D0")
    }

    @Test
    fun `vertical japanese dialogue is kept`() {
        assertKept("\u79C1\u306F\u304A\u524D\u306E\u4FDD\u8B77\u8005\u3068\u3057\u3066\u9580\u9650\u5185\u306B\u5E30\u3059\u7FA9\u52D9\u304C\u3042\u308B")
    }

    @Test
    fun `spanish dialogue is not a title`() {
        assertKept("No puedo creer esto")
        assertKept("¿Qué estás haciendo aquí?")
        assertKept("Hola, ¿qué tal?")
        assertKept("Te quiero mucho")
    }

    @Test
    fun `short hangul dialogue is kept`() {
        assertKept("안녕")
        assertKept("뭐야")
    }

    @Test
    fun `a two-character japanese sound effect is still dropped`() {
        assertDropped("\u30C9\u30C9")
    }

    @Test
    fun `repeated katakana display lettering is a sound effect`() {
        assertDropped("\u30D0\u30A2\u30A2\u30A2")
    }

    private fun assertKept(text: String) = assertFalse(candidate(text))

    private fun assertDropped(text: String) = assertTrue(candidate(text))

    private fun candidate(
        text: String,
        isTextBlock: Boolean = true,
        lineHeights: List<Int> = listOf(48, 50),
    ): Boolean = DecorativeTextGuard.shouldDrop(
        isTextBlock = isTextBlock,
        text = text,
        lineHeights = lineHeights,
        boxWidth = 600,
        boxHeight = 220,
        pageWidth = 900,
    )

    @Test
    fun `a short chinese logo in title type is artwork, not dialogue`() {
        // 末日剑神 drawn across the top of a chapter: four Han characters at 186px on an 800px page.
        // At body size four characters is a sentence, which is why the floor rises with the type.
        assertTrue(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "末日剑神",
                lineHeights = listOf(186),
                boxWidth = 518,
                boxHeight = 186,
                pageWidth = 800,
            ),
        )
    }

    @Test
    fun `a logo subtitle strip is artwork even though it covers little area`() {
        // 我震惊全球！ under the series logo: display type, but a wide shallow strip rather than a
        // caption box, so the area test alone let it through and the logo got repainted.
        assertTrue(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "我震惊全球",
                lineHeights = listOf(74),
                boxWidth = 382,
                boxHeight = 82,
                pageWidth = 800,
            ),
        )
    }

    @Test
    fun `short korean in title type is still dialogue`() {
        // Korean captions carry very short spoken lines at display size; the raised floor is for
        // Han on its own, never for Hangul.
        assertFalse(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "안녕",
                lineHeights = listOf(186),
                boxWidth = 518,
                boxHeight = 186,
                pageWidth = 800,
            ),
        )
    }

    @Test
    fun `short japanese with kana in title type is still dialogue`() {
        assertFalse(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "大丈夫か",
                lineHeights = listOf(186),
                boxWidth = 518,
                boxHeight = 186,
                pageWidth = 800,
            ),
        )
    }

    @Test
    fun `a full chinese line in title type is still dialogue`() {
        assertFalse(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "我震惊全球所有人都在看着我战斗",
                lineHeights = listOf(186),
                boxWidth = 518,
                boxHeight = 186,
                pageWidth = 800,
            ),
        )
    }

    @Test
    fun `short chinese at body size is left alone as dialogue`() {
        assertFalse(
            DecorativeTextGuard.shouldDrop(
                isTextBlock = true,
                text = "末日剑神",
                lineHeights = listOf(22),
                boxWidth = 160,
                boxHeight = 30,
                pageWidth = 800,
            ),
        )
    }
}
