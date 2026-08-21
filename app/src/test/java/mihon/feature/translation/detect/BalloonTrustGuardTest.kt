package mihon.feature.translation.detect

import mihon.feature.translation.model.BubbleBox
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BalloonTrustGuardTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        BubbleBox(left, top, right, bottom, confidence = 0.9f)

    @Test
    fun `a balloon holding one block of lettering is believed`() {
        val balloon = box(1100, 60, 1280, 250)
        val text = box(1106, 77, 1268, 232)
        assertTrue(BalloonTrustGuard.isBelievable(balloon, listOf(text)))
    }

    @Test
    fun `a balloon read as two stacked blocks is still one balloon`() {
        // The case the join exists for: one bubble the recogniser split in two.
        val balloon = box(100, 90, 210, 200)
        val upper = box(106, 96, 204, 140)
        val lower = box(106, 148, 204, 194)
        assertTrue(BalloonTrustGuard.isBelievable(balloon, listOf(upper, lower)))
    }

    @Test
    fun `a box dwarfing its lettering is a panel`() {
        // The chandelier false positive: a detection over most of a panel with one caption in it.
        val panel = box(0, 0, 1000, 600)
        val caption = box(400, 280, 520, 330)
        assertFalse(BalloonTrustGuard.isBelievable(panel, listOf(caption)))
    }

    @Test
    fun `a box spanning two balloons may not join them`() {
        // Measured on the page that set a sentence 716px wide inside a region 112px wide: the
        // detection ran 15..736 and covered a bubble at x=628 and a "NO..." at x=16.
        val spanning = box(15, 816, 736, 1058)
        val bubble = box(628, 867, 740, 1051)
        val stranger = box(16, 969, 88, 1001)
        assertFalse(BalloonTrustGuard.isBelievable(spanning, listOf(bubble, stranger)))
    }

    @Test
    fun `the hull of two blocks holds both`() {
        val hull = BalloonTrustGuard.hullOf(box(10, 20, 30, 40), box(25, 5, 60, 35))
        assertTrue(hull.left == 10 && hull.top == 5 && hull.right == 60 && hull.bottom == 40)
    }

    @Test
    fun `a balloon claiming nothing is not believed`() {
        assertFalse(BalloonTrustGuard.isBelievable(box(0, 0, 100, 100), emptyList()))
    }
}
