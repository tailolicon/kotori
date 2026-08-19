package mihon.feature.translation

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/** Distinguishes real webtoon slices from unrelated full pages shown in a continuous viewer. */
internal object ContinuousPageClassifier {

    data class SeamMetric(
        val crossDifference: Float,
        val withinDifference: Float,
        val blankEdge: Boolean,
    )

    fun shouldJoin(pages: List<Bitmap>): Boolean {
        if (pages.size < 2) return false
        val metrics = pages.zipWithNext(::measure)
        return shouldJoinMetrics(metrics)
    }

    fun shouldJoinMetrics(metrics: List<SeamMetric>): Boolean {
        val informative = metrics.filterNot(SeamMetric::blankEdge)
        if (informative.isEmpty()) return false
        val continuous = informative.count { metric ->
            metric.crossDifference <= maxOf(
                MAX_ABSOLUTE_SEAM_DIFFERENCE,
                metric.withinDifference * MAX_RELATIVE_DIFFERENCE + RELATIVE_SLOP,
            )
        }
        return continuous * REQUIRED_DENOMINATOR >= informative.size * REQUIRED_NUMERATOR
    }

    private fun measure(upper: Bitmap, lower: Bitmap): SeamMetric {
        val sampleWidth = minOf(upper.width, lower.width)
        if (sampleWidth <= 0 || upper.height < 2 || lower.height < 2) {
            return SeamMetric(Float.MAX_VALUE, 0f, blankEdge = false)
        }

        val step = maxOf(1, sampleWidth / MAX_SAMPLES)
        var cross = 0f
        var within = 0f
        val upperLuma = ArrayList<Float>(MAX_SAMPLES + 1)
        val lowerLuma = ArrayList<Float>(MAX_SAMPLES + 1)
        var count = 0
        var x = 0
        while (x < sampleWidth) {
            val upperEdge = upper.getPixel(x, upper.height - 1)
            val upperInside = upper.getPixel(x, upper.height - 2)
            val lowerEdge = lower.getPixel(x, 0)
            val lowerInside = lower.getPixel(x, 1)
            cross += colourDifference(upperEdge, lowerEdge)
            within += (colourDifference(upperEdge, upperInside) +
                colourDifference(lowerEdge, lowerInside)) / 2f
            upperLuma += luminance(upperEdge)
            lowerLuma += luminance(lowerEdge)
            count++
            x += step
        }

        // A cut through a white balloon is a white *row* with lettering a few pixels in. Treating
        // that row as a gutter left the two halves of the balloon as two pages, and each half was
        // then translated as a sentence. Ink in the edge band is the balloon continuing.
        val cutThroughLettering = hasInkNearBottom(upper) || hasInkNearTop(lower)
        val blank = isBlankWhite(upperLuma) && isBlankWhite(lowerLuma) && !cutThroughLettering
        val crossMean = cross / count
        return if (cutThroughLettering && blank.not()) {
            SeamMetric(minOf(crossMean, MAX_ABSOLUTE_SEAM_DIFFERENCE), within / count, blankEdge = false)
        } else if (cutThroughLettering) {
            SeamMetric(0f, within / count, blankEdge = false)
        } else {
            SeamMetric(crossMean, within / count, blankEdge = blank)
        }
    }

    private fun hasInkNearBottom(bitmap: Bitmap): Boolean =
        bandHasInk(bitmap, (bitmap.height - EDGE_BAND).coerceAtLeast(0), bitmap.height)

    private fun hasInkNearTop(bitmap: Bitmap): Boolean =
        bandHasInk(bitmap, 0, EDGE_BAND.coerceAtMost(bitmap.height))

    private fun bandHasInk(bitmap: Bitmap, top: Int, bottom: Int): Boolean {
        if (bottom <= top || bitmap.width <= 0) return false
        val stepX = maxOf(1, bitmap.width / MAX_SAMPLES)
        val stepY = maxOf(1, (bottom - top) / 4)
        val luma = ArrayList<Float>()
        var y = top
        while (y < bottom) {
            var x = 0
            while (x < bitmap.width) {
                luma += luminance(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        if (luma.size < 8) return false
        val sorted = luma.sorted()
        val paper = sorted[sorted.size / 2]
        val ink = luma.count { kotlin.math.abs(it - paper) >= INK_LUMA_GAP }
        return ink * INK_DENOMINATOR >= luma.size
    }

    private fun colourDifference(a: Int, b: Int): Float =
        (abs((a shr 16 and 0xff) - (b shr 16 and 0xff)) +
            abs((a shr 8 and 0xff) - (b shr 8 and 0xff)) +
            abs((a and 0xff) - (b and 0xff))) / 3f

    private fun luminance(colour: Int): Float =
        0.2126f * (colour shr 16 and 0xff) +
            0.7152f * (colour shr 8 and 0xff) +
            0.0722f * (colour and 0xff)

    private fun isBlankWhite(values: List<Float>): Boolean {
        if (values.isEmpty()) return false
        val mean = values.sum() / values.size
        if (mean < BLANK_MIN_LUMA) return false
        val variance = values.sumOf { value ->
            val distance = value - mean
            (distance * distance).toDouble()
        } / values.size
        return sqrt(variance).toFloat() <= BLANK_MAX_DEVIATION
    }

    private const val MAX_SAMPLES = 128
    private const val BLANK_MIN_LUMA = 246f
    private const val BLANK_MAX_DEVIATION = 4f
    private const val MAX_ABSOLUTE_SEAM_DIFFERENCE = 18f
    private const val MAX_RELATIVE_DIFFERENCE = 2.5f
    private const val RELATIVE_SLOP = 4f
    private const val REQUIRED_NUMERATOR = 2
    private const val REQUIRED_DENOMINATOR = 3
    /** How far into a page to look for lettering at a suspected cut. */
    private const val EDGE_BAND = 28
    /** Luma distance from the band's median that counts as ink, not paper. */
    private const val INK_LUMA_GAP = 28f
    /** Share of the band that must be ink before the cut is treated as going through a balloon. */
    private const val INK_DENOMINATOR = 12
}
