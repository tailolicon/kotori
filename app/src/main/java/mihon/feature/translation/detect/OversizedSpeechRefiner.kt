package mihon.feature.translation.detect

/** Replaces a panel-sized speech false-positive with the precise whole-page OCR block inside it. */
internal object OversizedSpeechRefiner {

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height
    }

    fun shouldReplace(speech: Bounds, text: Bounds): Boolean {
        if (speech.area <= 0L || text.area <= 0L) return false
        val overlapLeft = maxOf(speech.left, text.left)
        val overlapTop = maxOf(speech.top, text.top)
        val overlapRight = minOf(speech.right, text.right)
        val overlapBottom = minOf(speech.bottom, text.bottom)
        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) return false
        val coveredText = (overlapRight - overlapLeft).toLong() * (overlapBottom - overlapTop)
        val coverage = coveredText.toFloat() / text.area
        val muchWider = speech.width >= text.width * MIN_WIDTH_MULTIPLIER
        val muchLarger = speech.area >= text.area * MIN_AREA_MULTIPLIER
        return coverage >= MIN_TEXT_COVERAGE && muchWider && muchLarger
    }

    private const val MIN_TEXT_COVERAGE = 0.55f
    private const val MIN_WIDTH_MULTIPLIER = 3
    private const val MIN_AREA_MULTIPLIER = 4
}
