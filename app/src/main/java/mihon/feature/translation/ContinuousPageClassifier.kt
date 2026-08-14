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

        val blank = isBlankWhite(upperLuma) && isBlankWhite(lowerLuma)
        return SeamMetric(cross / count, within / count, blank)
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
}
