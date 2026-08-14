package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceEchoHeuristicTest {

    @Test
    fun `obvious English dialogue is retried for Vietnamese target`() {
        listOf("YES.", "...WAIT.", "IS ME...", "YOU DID WELL.", "IT'S TIME NOW.", "ENEMIEEES!")
            .forEach { text -> assertTrue(SourceEchoHeuristic.isLikely(text, "en", "vi"), text) }
    }

    @Test
    fun `proper names and translated Vietnamese are not treated as echoes`() {
        listOf("Kyouka Uzen", "Himari Azuma", "Tôi đã hiểu rồi", "Mato")
            .forEach { text -> assertFalse(SourceEchoHeuristic.isLikely(text, "en", "vi"), text) }
    }

    @Test
    fun `heuristic is disabled for other language pairs`() {
        assertFalse(SourceEchoHeuristic.isLikely("YES", "en", "ja"))
    }

    @Test
    fun `automatic source detection still catches English echo for Vietnamese`() {
        assertTrue(SourceEchoHeuristic.isLikely("WAIT!", "auto", "vi"))
    }
}
