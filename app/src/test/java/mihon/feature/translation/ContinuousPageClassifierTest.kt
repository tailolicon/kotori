package mihon.feature.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuousPageClassifierTest {

    @Test
    fun `continuous webtoon seams are joined`() {
        assertTrue(
            ContinuousPageClassifier.shouldJoinMetrics(
                listOf(metric(7f, 5f), metric(11f, 6f), metric(9f, 5f), metric(42f, 5f)),
            ),
        )
    }

    @Test
    fun `unrelated manga page edges stay native resolution`() {
        assertFalse(
            ContinuousPageClassifier.shouldJoinMetrics(
                listOf(metric(70f, 5f), metric(55f, 8f), metric(12f, 6f), metric(62f, 4f)),
            ),
        )
    }

    @Test
    fun `blank white gutters are not evidence of continuity`() {
        assertFalse(
            ContinuousPageClassifier.shouldJoinMetrics(
                listOf(metric(0f, 0f, blank = true), metric(0f, 0f, blank = true)),
            ),
        )
    }

    @Test
    fun `blank gutters are ignored when real seams prove continuity`() {
        assertTrue(
            ContinuousPageClassifier.shouldJoinMetrics(
                listOf(metric(0f, 0f, blank = true), metric(8f, 5f), metric(10f, 5f)),
            ),
        )
    }

    private fun metric(cross: Float, within: Float, blank: Boolean = false) =
        ContinuousPageClassifier.SeamMetric(cross, within, blank)
}
