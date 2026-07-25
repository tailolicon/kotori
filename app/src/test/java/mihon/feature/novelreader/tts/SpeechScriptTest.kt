package mihon.feature.novelreader.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechScriptTest {

    @Test
    fun `splits a paragraph into sentences`() {
        val script = buildSpeechScript(
            listOf("Trời hôm nay thật đẹp. Tôi bước ra khỏi nhà và hít một hơi thật sâu! Cậu nghe thấy chứ?"),
        )

        assertEquals(
            listOf(
                "Trời hôm nay thật đẹp.",
                "Tôi bước ra khỏi nhà và hít một hơi thật sâu!",
                "Cậu nghe thấy chứ?",
            ),
            script.sentences.map { it.text },
        )
    }

    @Test
    fun `does not split inside a decimal number`() {
        val text = "Giá của nó là 3.5 triệu đồng, khá đắt so với mặt bằng chung hiện nay."
        val script = buildSpeechScript(listOf(text))

        assertEquals(listOf(text), script.sentences.map { it.text })
    }

    @Test
    fun `keeps a closing quote with the sentence it ends`() {
        val script = buildSpeechScript(listOf("\"Cậu điên rồi!\" Aoi hét lên. Tôi chỉ biết im lặng…"))

        assertEquals(
            listOf("\"Cậu điên rồi!\"", "Aoi hét lên.", "Tôi chỉ biết im lặng…"),
            script.sentences.map { it.text },
        )
    }

    @Test
    fun `folds fragments too short to be their own utterance`() {
        val script = buildSpeechScript(listOf("Ừ. Được thôi. Vậy thì chúng ta cùng đi nhé, tôi đợi cậu."))

        assertEquals(
            listOf("Ừ. Được thôi.", "Vậy thì chúng ta cùng đi nhé, tôi đợi cậu."),
            script.sentences.map { it.text },
        )
    }

    @Test
    fun `breaks up a paragraph with no sentence punctuation`() {
        val runOn = "một đoạn văn rất dài không có dấu chấm nào cả ".repeat(20).trim()
        val script = buildSpeechScript(listOf(runOn))

        assertTrue(script.size > 1, "a run-on paragraph must not become one utterance")
        assertTrue(script.sentences.all { it.text.length <= 320 }, "no utterance may exceed the cap")
        // Nothing may be lost in the split: every word still has to be read out.
        assertEquals(
            runOn.replace(" ", ""),
            script.sentences.joinToString("") { it.text }.replace(" ", ""),
        )
    }

    @Test
    fun `illustrations keep their slot so highlights land on the right paragraph`() {
        val script = buildSpeechScript(
            listOf("Đoạn đầu tiên ở đây.", null, "Đoạn sau bức tranh minh hoạ."),
        )

        assertEquals(listOf(0, 2), script.sentences.map { it.blockIndex })
        assertTrue(script.sentencesIn(1).isEmpty())
    }

    @Test
    fun `maps a tap offset back to the sentence under it`() {
        val text = "Câu thứ nhất ở đây. Câu thứ hai nằm ngay sau nó."
        val script = buildSpeechScript(listOf(text))

        val second = script.sentenceAt(blockIndex = 0, offset = text.indexOf("hai"))

        assertNotNull(second)
        assertEquals("Câu thứ hai nằm ngay sau nó.", second!!.text)
    }

    @Test
    fun `sentence ranges point at the original paragraph text`() {
        val text = "Câu thứ nhất ở đây. Câu thứ hai nằm ngay sau nó."
        val script = buildSpeechScript(listOf(text))

        script.sentences.forEach { sentence ->
            assertEquals(
                sentence.text,
                text.substring(sentence.range.first, sentence.range.last + 1).trim(),
            )
        }
    }

}
