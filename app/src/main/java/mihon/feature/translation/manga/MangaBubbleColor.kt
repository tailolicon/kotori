package mihon.feature.translation.manga

/**
 * Port of Manga-Translator `process_bubble.get_bubble_background_color` / `is_dark_bubble`.
 * Pixels are packed ARGB; work is done in RGB so the voted colour matches OpenCV BGR channel-wise.
 */
internal object MangaBubbleColor {

    const val DARK_THRESHOLD = 100

    fun isDark(pixels: IntArray, meanGray: Int = -1): Boolean {
        val intensity = if (meanGray >= 0) meanGray else meanIntensity(pixels)
        return intensity < DARK_THRESHOLD
    }

    fun meanIntensity(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 255
        var sum = 0L
        for (color in pixels) sum += MangaPixels.gray(color)
        return (sum / pixels.size).toInt()
    }

    fun backgroundColor(pixels: IntArray, width: Int, height: Int): Int {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return MangaPixels.rgb(255, 255, 255)
        val marginY = maxOf(10, height / 5)
        val marginX = maxOf(10, width / 5)
        val top = marginY
        val bottom = (height - marginY).coerceAtLeast(top + 1)
        val left = marginX
        val right = (width - marginX).coerceAtLeast(left + 1)
        val center = ArrayList<Int>((bottom - top) * (right - left))
        for (y in top until bottom.coerceAtMost(height)) {
            val row = y * width
            for (x in left until right.coerceAtMost(width)) {
                center += pixels[row + x]
            }
        }
        if (center.isEmpty()) {
            pixels.forEach { center += it }
        }
        val gray = IntArray(center.size) { MangaPixels.gray(center[it]) }
        val median = median(gray)
        val bgMask = BooleanArray(center.size) { i ->
            if (median > 128) gray[i] > 180 else gray[i] < 80
        }
        val kept = ArrayList<Int>(center.size)
        for (i in center.indices) if (bgMask[i]) kept += center[i]
        val bg = if (kept.size > 100) kept else center

        val colors = arrayOf(colorByMode(bg), colorByHistogram(bg), medianColor(bg))
        var best = colors[0]
        var minDist = Int.MAX_VALUE
        for (i in colors.indices) {
            var total = 0
            for (j in colors.indices) {
                if (i == j) continue
                total += channelDist(colors[i], colors[j])
            }
            if (total < minDist) {
                minDist = total
                best = colors[i]
            }
        }
        return best
    }

    internal fun colorByMode(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return MangaPixels.rgb(255, 255, 255)
        val counts = HashMap<Int, Int>(pixels.size)
        var bestCode = 0
        var bestCount = -1
        for (color in pixels) {
            val r = (MangaPixels.red(color) / 8) * 8
            val g = (MangaPixels.green(color) / 8) * 8
            val b = (MangaPixels.blue(color) / 8) * 8
            // MT packs B*65536+G*256+R; equivalent grouping, we store RGB.
            val code = (r shl 16) or (g shl 8) or b
            val next = (counts[code] ?: 0) + 1
            counts[code] = next
            if (next > bestCount) {
                bestCount = next
                bestCode = code
            }
        }
        return MangaPixels.rgb(bestCode shr 16 and 0xFF, bestCode shr 8 and 0xFF, bestCode and 0xFF)
    }

    internal fun colorByHistogram(pixels: List<Int>, bins: Int = 32): Int {
        if (pixels.isEmpty()) return MangaPixels.rgb(255, 255, 255)
        val histR = IntArray(bins)
        val histG = IntArray(bins)
        val histB = IntArray(bins)
        val binWidth = 256 / bins
        for (color in pixels) {
            histR[MangaPixels.red(color) / binWidth]++
            histG[MangaPixels.green(color) / binWidth]++
            histB[MangaPixels.blue(color) / binWidth]++
        }
        fun peak(hist: IntArray): Int = hist.indices.maxBy { hist[it] } * binWidth + binWidth / 2
        return MangaPixels.rgb(peak(histR), peak(histG), peak(histB))
    }

    private fun medianColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return MangaPixels.rgb(255, 255, 255)
        val r = IntArray(pixels.size) { MangaPixels.red(pixels[it]) }
        val g = IntArray(pixels.size) { MangaPixels.green(pixels[it]) }
        val b = IntArray(pixels.size) { MangaPixels.blue(pixels[it]) }
        return MangaPixels.rgb(median(r), median(g), median(b))
    }

    private fun median(values: IntArray): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun channelDist(a: Int, b: Int): Int =
        kotlin.math.abs(MangaPixels.red(a) - MangaPixels.red(b)) +
            kotlin.math.abs(MangaPixels.green(a) - MangaPixels.green(b)) +
            kotlin.math.abs(MangaPixels.blue(a) - MangaPixels.blue(b))
}
