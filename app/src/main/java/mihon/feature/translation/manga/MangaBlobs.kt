package mihon.feature.translation.manga

/**
 * OpenCV-free port of the blob steps in `detect_bubbles.detect_black_bubbles` and
 * `process_bubble.process_bubble_auto`.
 */
internal object MangaBlobs {

    data class Blob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val area: Int,
        val pixels: IntArray,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val rectArea: Int get() = width * height
    }

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

    fun detectBlackBubbles(pixels: IntArray, width: Int, height: Int): List<Box> {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return emptyList()
        val maxArea = (width.toLong() * height * BLACK_MAX_AREA_RATIO).toInt()
        val binary = BooleanArray(pixels.size) { MangaPixels.gray(pixels[it]) < BLACK_THRESHOLD }
        val closed = morphology(binary, width, height, close = true)
        val opened = morphology(closed, width, height, close = false)
        val blobs = connectedComponents(opened, width, height)
        val detections = ArrayList<Box>()
        for (blob in blobs) {
            if (blob.area < BLACK_MIN_AREA || blob.area > maxArea) continue
            val aspect = if (blob.height > 0) blob.width.toFloat() / blob.height else 0f
            if (aspect < BLACK_MIN_ASPECT || aspect > BLACK_MAX_ASPECT) continue
            val fillRatio = if (blob.rectArea > 0) blob.area.toFloat() / blob.rectArea else 0f
            if (fillRatio < 0.3f) continue
            var intensitySum = 0L
            var count = 0
            for (y in blob.minY..blob.maxY) {
                val row = y * width
                for (x in blob.minX..blob.maxX) {
                    intensitySum += MangaPixels.gray(pixels[row + x])
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

    internal fun morphology(src: BooleanArray, width: Int, height: Int, close: Boolean): BooleanArray {
        return if (close) erode(dilate(src, width, height), width, height)
        else dilate(erode(src, width, height), width, height)
    }

    internal fun connectedComponents(binary: BooleanArray, width: Int, height: Int): List<Blob> {
        val seen = BooleanArray(binary.size)
        val blobs = ArrayList<Blob>()
        val stackX = IntArray(binary.size)
        val stackY = IntArray(binary.size)
        for (start in binary.indices) {
            if (!binary[start] || seen[start]) continue
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            val members = ArrayList<Int>()
            var sp = 0
            stackX[sp] = start % width
            stackY[sp] = start / width
            sp++
            seen[start] = true
            while (sp > 0) {
                sp--
                val x = stackX[sp]
                val y = stackY[sp]
                members += y * width + x
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val ni = ny * width + nx
                        if (!binary[ni] || seen[ni]) continue
                        seen[ni] = true
                        stackX[sp] = nx
                        stackY[sp] = ny
                        sp++
                    }
                }
            }
            blobs += Blob(minX, minY, maxX, maxY, members.size, members.toIntArray())
        }
        return blobs
    }

    /**
     * Pixels reachable from the image border without crossing [wall]. Everything else is the
     * filled contour interior, matching OpenCV `drawContours(..., FILLED)`.
     */
    internal fun floodExterior(wall: BooleanArray, width: Int, height: Int): BooleanArray {
        val exterior = BooleanArray(wall.size)
        val stackX = IntArray(wall.size)
        val stackY = IntArray(wall.size)
        var sp = 0
        fun push(x: Int, y: Int) {
            val i = y * width + x
            if (wall[i] || exterior[i]) return
            exterior[i] = true
            stackX[sp] = x
            stackY[sp] = y
            sp++
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
            sp--
            val x = stackX[sp]
            val y = stackY[sp]
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
        val blobs = connectedComponents(binary, width, height)
        val largest = blobs.maxByOrNull { it.area }
        val out = pixels.copyOf()
        if (largest == null) {
            for (i in out.indices) out[i] = fillColor
            return FillResult(out, 0, 0, width, height, isDark, fillColor)
        }
        // cv2.drawContours(..., FILLED) paints the outer outline *and* holes (lettering).
        val wall = BooleanArray(out.size)
        for (index in largest.pixels) wall[index] = true
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

    private fun dilate(src: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var on = false
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        if (!ellipse5(kx, ky)) continue
                        val nx = x + kx
                        val ny = y + ky
                        if (nx in 0 until width && ny in 0 until height && src[ny * width + nx]) {
                            on = true
                            break
                        }
                    }
                    if (on) break
                }
                out[y * width + x] = on
            }
        }
        return out
    }

    private fun erode(src: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var on = true
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        if (!ellipse5(kx, ky)) continue
                        val nx = x + kx
                        val ny = y + ky
                        if (nx !in 0 until width || ny !in 0 until height || !src[ny * width + nx]) {
                            on = false
                            break
                        }
                    }
                    if (!on) break
                }
                out[y * width + x] = on
            }
        }
        return out
    }

    /** 5x5 ellipse structuring element matching `cv2.MORPH_ELLIPSE, (5, 5)`. */
    private fun ellipse5(kx: Int, ky: Int): Boolean {
        val ax = kx / 2.0
        val ay = ky / 2.0
        return ax * ax + ay * ay <= 1.0001
    }

    private fun adaptiveGaussian(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val gray = IntArray(pixels.size) { MangaPixels.gray(pixels[it]) }
        val out = BooleanArray(pixels.size)
        val radius = 5
        val sigma = 1.7
        val kernel = DoubleArray(11 * 11)
        var ksum = 0.0
        for (y in 0..10) {
            for (x in 0..10) {
                val dx = x - radius
                val dy = y - radius
                val v = kotlin.math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
                kernel[y * 11 + x] = v
                ksum += v
            }
        }
        for (i in kernel.indices) kernel[i] /= ksum
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0.0
                for (ky in 0..10) {
                    val iy = (y + ky - radius).coerceIn(0, height - 1)
                    for (kx in 0..10) {
                        val ix = (x + kx - radius).coerceIn(0, width - 1)
                        acc += gray[iy * width + ix] * kernel[ky * 11 + kx]
                    }
                }
                out[y * width + x] = gray[y * width + x] > acc - 2.0
            }
        }
        return out
    }
}
