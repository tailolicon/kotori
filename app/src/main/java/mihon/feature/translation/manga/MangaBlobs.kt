package mihon.feature.translation.manga

/**
 * OpenCV-free port of the blob steps in `detect_bubbles.detect_black_bubbles` and
 * `process_bubble.process_bubble_auto`.
 *
 * The Python original leans on OpenCV, where a morphological pass over a 6-megapixel page is a few
 * milliseconds of C. Written out naively in Kotlin the same pass is a 25-tap neighbourhood per
 * pixel and takes seconds, which on a phone is the difference between a page that translates while
 * you read and one you wait for. Both hot stages are therefore expressed as separable 1-D passes
 * over a running window: identical results, linear in the number of pixels rather than in
 * pixels x kernel.
 */
internal object MangaBlobs {

    data class Blob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val area: Int,
        /** Label this blob carries in [Labelling.labels]. */
        val label: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val rectArea: Int get() = width * height
    }

    /** Connected-component labelling: `labels[i]` is 0 for background, or the blob's 1-based id. */
    class Labelling(val labels: IntArray, val blobs: List<Blob>)

    data class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float,
        val isDark: Boolean,
    )

    data class FillResult(
        val pixels: IntArray,
        val contourLeft: Int,
        val contourTop: Int,
        val contourRight: Int,
        val contourBottom: Int,
        val isDark: Boolean,
        val fillColor: Int,
    )

    const val BLACK_THRESHOLD = 50
    const val BLACK_MIN_AREA = 1000
    const val BLACK_MAX_AREA_RATIO = 0.4f
    const val BLACK_MIN_ASPECT = 0.2f
    const val BLACK_MAX_ASPECT = 5.0f
    const val IOU_THRESHOLD = 0.5f

    /**
     * Longest side the black-bubble search works at.
     *
     * The search only has to place a bounding box around a large flat dark region, and those survive
     * downsampling perfectly — while the morphology and labelling behind it cost time proportional
     * to the pixel count. A full 2069x2880 manga page is 6 MP; capped here it is under 1 MP, and the
     * boxes are scaled back to page space before anyone sees them.
     */
    const val BLACK_SEARCH_MAX_SIDE = 1024

    /**
     * Finds filled dark regions the bubble model tends to miss, as `detect_black_bubbles` does.
     *
     * Runs on a downsampled copy for speed (see [BLACK_SEARCH_MAX_SIDE]); every threshold that is
     * expressed in pixels is scaled with it so the same regions qualify.
     */
    fun detectBlackBubbles(pixels: IntArray, width: Int, height: Int): List<Box> {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return emptyList()

        val step = downsampleStep(width, height)
        return if (step == 1) {
            detectBlackBubblesAt(pixels, width, height, minArea = BLACK_MIN_AREA, scale = 1)
        } else {
            val smallWidth = (width + step - 1) / step
            val smallHeight = (height + step - 1) / step
            val small = IntArray(smallWidth * smallHeight)
            for (y in 0 until smallHeight) {
                val srcRow = (y * step) * width
                val dstRow = y * smallWidth
                for (x in 0 until smallWidth) {
                    small[dstRow + x] = pixels[srcRow + x * step]
                }
            }
            // Area is a count of pixels, so it shrinks by step^2 in the smaller image.
            val minArea = (BLACK_MIN_AREA / (step * step)).coerceAtLeast(16)
            detectBlackBubblesAt(small, smallWidth, smallHeight, minArea, scale = step)
                .map { box ->
                    box.copy(
                        left = (box.left * step).coerceIn(0, width),
                        top = (box.top * step).coerceIn(0, height),
                        right = (box.right * step).coerceIn(0, width),
                        bottom = (box.bottom * step).coerceIn(0, height),
                    )
                }
        }
    }

    private fun downsampleStep(width: Int, height: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= BLACK_SEARCH_MAX_SIDE) return 1
        return ((longest + BLACK_SEARCH_MAX_SIDE - 1) / BLACK_SEARCH_MAX_SIDE).coerceAtLeast(2)
    }

    private fun detectBlackBubblesAt(
        pixels: IntArray,
        width: Int,
        height: Int,
        minArea: Int,
        @Suppress("UNUSED_PARAMETER") scale: Int,
    ): List<Box> {
        val maxArea = (width.toLong() * height * BLACK_MAX_AREA_RATIO).toInt()
        val gray = IntArray(pixels.size) { MangaPixels.gray(pixels[it]) }
        val binary = BooleanArray(pixels.size) { gray[it] < BLACK_THRESHOLD }
        val closed = morphology(binary, width, height, close = true)
        val opened = morphology(closed, width, height, close = false)
        val labelling = label(opened, width, height)
        val detections = ArrayList<Box>()
        for (blob in labelling.blobs) {
            if (blob.area < minArea || blob.area > maxArea) continue
            val aspect = if (blob.height > 0) blob.width.toFloat() / blob.height else 0f
            if (aspect < BLACK_MIN_ASPECT || aspect > BLACK_MAX_ASPECT) continue
            val fillRatio = if (blob.rectArea > 0) blob.area.toFloat() / blob.rectArea else 0f
            if (fillRatio < 0.3f) continue
            var intensitySum = 0L
            var count = 0
            for (y in blob.minY..blob.maxY) {
                val row = y * width
                for (x in blob.minX..blob.maxX) {
                    intensitySum += gray[row + x]
                    count++
                }
            }
            val mean = if (count > 0) (intensitySum / count).toInt() else 255
            if (mean > BLACK_THRESHOLD + 30) continue
            val confidence = minOf(0.8f, fillRatio * (1f - mean / 255f))
            detections += Box(blob.minX, blob.minY, blob.minX + blob.width, blob.minY + blob.height, confidence, true)
        }
        return detections
    }

    fun processAuto(
        pixels: IntArray,
        width: Int,
        height: Int,
        forceDark: Boolean,
    ): FillResult {
        val fill = MangaBubbleColor.backgroundColor(pixels, width, height)
        val dark = forceDark || MangaBubbleColor.isDark(pixels)
        return if (dark) processDark(pixels, width, height, fill) else processLight(pixels, width, height, fill)
    }

    fun processDark(pixels: IntArray, width: Int, height: Int, fillColor: Int): FillResult {
        val binary = BooleanArray(pixels.size) { MangaPixels.gray(pixels[it]) < 50 }
        return fillLargest(pixels, binary, width, height, fillColor, isDark = true)
    }

    fun processLight(pixels: IntArray, width: Int, height: Int, fillColor: Int): FillResult {
        val bgIntensity = (
            MangaPixels.red(fillColor) + MangaPixels.green(fillColor) + MangaPixels.blue(fillColor)
            ) / 3
        val binary = when {
            bgIntensity > 200 -> BooleanArray(pixels.size) { MangaPixels.gray(pixels[it]) > 240 }
            bgIntensity < 50 -> BooleanArray(pixels.size) { MangaPixels.gray(pixels[it]) < 50 }
            else -> adaptiveGaussian(pixels, width, height)
        }
        return fillLargest(pixels, binary, width, height, fillColor, isDark = false)
    }

    fun nms(boxes: List<Box>, iouThreshold: Float = IOU_THRESHOLD): List<Box> {
        if (boxes.size <= 1) return boxes
        val remaining = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<Box>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            keep += best
            remaining.removeAll { iou(best, it) >= iouThreshold }
        }
        return keep
    }

    fun iou(a: Box, b: Box): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toFloat() * (bottom - top)
        val areaA = (a.right - a.left).toFloat() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toFloat() * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /**
     * `cv2.morphologyEx` with `getStructuringElement(MORPH_ELLIPSE, (5, 5))`, which is
     *
     *     . . X . .
     *     X X X X X
     *     X X X X X
     *     X X X X X
     *     . . X . .
     *
     * — the union of a 5x3 rectangle and a 1x5 one. Dilation distributes over that union and erosion
     * intersects over it, and a rectangle is separable, so the whole thing is four 1-D passes rather
     * than a 25-tap neighbourhood walk.
     */
    internal fun morphology(src: BooleanArray, width: Int, height: Int, close: Boolean): BooleanArray {
        return if (close) erode(dilate(src, width, height), width, height)
        else dilate(erode(src, width, height), width, height)
    }

    internal fun dilate(src: BooleanArray, width: Int, height: Int): BooleanArray {
        val wide = dilateRect(src, width, height, radiusX = 2, radiusY = 1)
        val tall = dilateRect(src, width, height, radiusX = 0, radiusY = 2)
        for (i in wide.indices) wide[i] = wide[i] || tall[i]
        return wide
    }

    internal fun erode(src: BooleanArray, width: Int, height: Int): BooleanArray {
        val wide = erodeRect(src, width, height, radiusX = 2, radiusY = 1)
        val tall = erodeRect(src, width, height, radiusX = 0, radiusY = 2)
        for (i in wide.indices) wide[i] = wide[i] && tall[i]
        return wide
    }

    /**
     * Labels 8-connected components of [binary].
     *
     * Iterative flood fill over an index stack. The previous version kept every member pixel of
     * every blob in an `ArrayList<Int>`, which boxes one object per pixel — on a full page that is
     * millions of allocations for information only the largest blob ever needed.
     */
    internal fun label(binary: BooleanArray, width: Int, height: Int): Labelling {
        val labels = IntArray(binary.size)
        val blobs = ArrayList<Blob>()
        if (width <= 0 || height <= 0) return Labelling(labels, blobs)
        val stack = IntArray(binary.size)
        var next = 0
        for (start in binary.indices) {
            if (!binary[start] || labels[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var area = 0
            while (sp > 0) {
                val index = stack[--sp]
                val x = index % width
                val y = index / width
                area++
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                val yFrom = if (y > 0) -1 else 0
                val yTo = if (y + 1 < height) 1 else 0
                val xFrom = if (x > 0) -1 else 0
                val xTo = if (x + 1 < width) 1 else 0
                for (dy in yFrom..yTo) {
                    val row = (y + dy) * width
                    for (dx in xFrom..xTo) {
                        if (dx == 0 && dy == 0) continue
                        val neighbour = row + x + dx
                        if (!binary[neighbour] || labels[neighbour] != 0) continue
                        labels[neighbour] = next
                        stack[sp++] = neighbour
                    }
                }
            }
            blobs += Blob(minX, minY, maxX, maxY, area, next)
        }
        return Labelling(labels, blobs)
    }

    /**
     * Pixels reachable from the image border without crossing [wall]. Everything else is the
     * filled contour interior, matching OpenCV `drawContours(..., FILLED)`.
     */
    internal fun floodExterior(wall: BooleanArray, width: Int, height: Int): BooleanArray {
        val exterior = BooleanArray(wall.size)
        val stack = IntArray(wall.size)
        var sp = 0
        fun push(x: Int, y: Int) {
            val i = y * width + x
            if (wall[i] || exterior[i]) return
            exterior[i] = true
            stack[sp++] = i
        }
        for (x in 0 until width) {
            push(x, 0)
            if (height > 1) push(x, height - 1)
        }
        for (y in 1 until height - 1) {
            push(0, y)
            if (width > 1) push(width - 1, y)
        }
        while (sp > 0) {
            val index = stack[--sp]
            val x = index % width
            val y = index / width
            if (x > 0) push(x - 1, y)
            if (x + 1 < width) push(x + 1, y)
            if (y > 0) push(x, y - 1)
            if (y + 1 < height) push(x, y + 1)
        }
        return exterior
    }

    private fun fillLargest(
        pixels: IntArray,
        binary: BooleanArray,
        width: Int,
        height: Int,
        fillColor: Int,
        isDark: Boolean,
    ): FillResult {
        val labelling = label(binary, width, height)
        val largest = labelling.blobs.maxByOrNull { it.area }
        val out = pixels.copyOf()
        if (largest == null) {
            for (i in out.indices) out[i] = fillColor
            return FillResult(out, 0, 0, width, height, isDark, fillColor)
        }
        // cv2.drawContours(..., FILLED) paints the outer outline *and* holes (lettering).
        val wall = BooleanArray(out.size)
        val labels = labelling.labels
        for (i in labels.indices) {
            if (labels[i] == largest.label) wall[i] = true
        }
        val exterior = floodExterior(wall, width, height)
        for (i in out.indices) {
            if (!exterior[i]) out[i] = fillColor
        }
        return FillResult(
            pixels = out,
            contourLeft = largest.minX,
            contourTop = largest.minY,
            contourRight = largest.maxX + 1,
            contourBottom = largest.maxY + 1,
            isDark = isDark,
            fillColor = fillColor,
        )
    }

    /** Separable rectangular dilation: true where any pixel in the (2rx+1)x(2ry+1) box is true. */
    private fun dilateRect(
        src: BooleanArray,
        width: Int,
        height: Int,
        radiusX: Int,
        radiusY: Int,
    ): BooleanArray {
        val horizontal = if (radiusX == 0) src.copyOf() else sweepRows(src, width, height, radiusX, any = true)
        return if (radiusY == 0) horizontal else sweepColumns(horizontal, width, height, radiusY, any = true)
    }

    /** Separable rectangular erosion: true only where every pixel in the box is true. */
    private fun erodeRect(
        src: BooleanArray,
        width: Int,
        height: Int,
        radiusX: Int,
        radiusY: Int,
    ): BooleanArray {
        val horizontal = if (radiusX == 0) src.copyOf() else sweepRows(src, width, height, radiusX, any = false)
        return if (radiusY == 0) horizontal else sweepColumns(horizontal, width, height, radiusY, any = false)
    }

    /**
     * One horizontal pass with a running count of set pixels in the window.
     *
     * Out-of-image neighbours count as *unset*, which is what OpenCV's default `BORDER_CONSTANT`
     * does for these operations: a dilation cannot invent a pixel outside the image, and an erosion
     * at the border sees background and switches off.
     */
    private fun sweepRows(src: BooleanArray, width: Int, height: Int, radius: Int, any: Boolean): BooleanArray {
        val out = BooleanArray(src.size)
        val span = 2 * radius + 1
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0 until minOf(radius + 1, width)) if (src[row + x]) count++
            for (x in 0 until width) {
                out[row + x] = if (any) count > 0 else count == span
                val leaving = x - radius
                val entering = x + radius + 1
                if (leaving >= 0 && src[row + leaving]) count--
                if (entering < width && src[row + entering]) count++
            }
        }
        return out
    }

    /** Vertical twin of [sweepRows]. */
    private fun sweepColumns(src: BooleanArray, width: Int, height: Int, radius: Int, any: Boolean): BooleanArray {
        val out = BooleanArray(src.size)
        val span = 2 * radius + 1
        for (x in 0 until width) {
            var count = 0
            for (y in 0 until minOf(radius + 1, height)) if (src[y * width + x]) count++
            for (y in 0 until height) {
                out[y * width + x] = if (any) count > 0 else count == span
                val leaving = y - radius
                val entering = y + radius + 1
                if (leaving >= 0 && src[leaving * width + x]) count--
                if (entering < height && src[entering * width + x]) count++
            }
        }
        return out
    }

    /**
     * `cv2.adaptiveThreshold(..., ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, 11, 2)`.
     *
     * The Gaussian is separable, so the 11x11 window is two 11-tap passes rather than 121 taps per
     * pixel — the same numbers, an order of magnitude less arithmetic.
     */
    private fun adaptiveGaussian(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val gray = IntArray(pixels.size) { MangaPixels.gray(pixels[it]) }
        val out = BooleanArray(pixels.size)
        val radius = 5
        val sigma = 1.7
        val kernel = DoubleArray(2 * radius + 1)
        var ksum = 0.0
        for (i in kernel.indices) {
            val d = i - radius
            val v = kotlin.math.exp(-(d * d) / (2 * sigma * sigma))
            kernel[i] = v
            ksum += v
        }
        for (i in kernel.indices) kernel[i] /= ksum

        val horizontal = DoubleArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (k in kernel.indices) {
                    val ix = (x + k - radius).coerceIn(0, width - 1)
                    acc += gray[row + ix] * kernel[k]
                }
                horizontal[row + x] = acc
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (k in kernel.indices) {
                    val iy = (y + k - radius).coerceIn(0, height - 1)
                    acc += horizontal[iy * width + x] * kernel[k]
                }
                out[row + x] = gray[row + x] > acc - 2.0
            }
        }
        return out
    }
}
