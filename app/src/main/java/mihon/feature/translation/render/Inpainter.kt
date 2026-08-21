package mihon.feature.translation.render

import kotlin.math.max

/**
 * Reconstructs the pixels hidden by a glyph mask.
 *
 * Strategy is a multi-scale push-pull solve: build a weighted pyramid where masked pixels carry zero
 * weight, propagate colour inwards from the coarsest level, then relax at each level on the way back
 * up. The result follows whatever was behind the text — a paper-white bubble, a soft gradient, a
 * screentone field, a colour panel — instead of stamping one averaged colour over it.
 *
 * This is what "fill it with the same colour" has to mean in practice: not one colour for the whole
 * bubble, but the right colour for every individual pixel.
 *
 * Cost is linear in region area, a fraction of a millisecond for a typical bubble, which is why the
 * whole page can be processed without a visible stall.
 */
internal object Inpainter {

    private const val PYRAMID_MIN_SIDE = 2
    private const val RELAX_ITERATIONS = 4
    private const val EPSILON = 1e-4f

    /** Below this many usable neighbours there is no texture to measure, only noise about noise. */
    private const val MIN_GRAIN_SAMPLES = 64

    /**
     * Mean absolute residual below which the surroundings count as smooth.
     *
     * Paper and flat balloon interiors sit near zero; screentone, film grain and inked hatching run
     * well above it. Set low enough to catch faint tone, high enough that a clean bubble is never
     * given noise it did not have.
     */
    private const val MIN_GRAIN_STRENGTH = 3.5f

    /**
     * Fills every pixel where `mask` is true, in place.
     *
     * @param pixels ARGB pixels, row-major, mutated in place
     * @param mask one entry per pixel; true means "unknown, reconstruct me"
     */
    fun fill(pixels: IntArray, width: Int, height: Int, mask: BooleanArray) {
        if (width <= 0 || height <= 0) return

        var anyMasked = false
        for (value in mask) {
            if (value) {
                anyMasked = true
                break
            }
        }
        if (!anyMasked) return

        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)
        val weight = FloatArray(size)

        for (i in 0 until size) {
            if (mask[i]) continue
            val p = pixels[i]
            red[i] = ((p shr 16) and 0xFF).toFloat()
            green[i] = ((p shr 8) and 0xFF).toFloat()
            blue[i] = (p and 0xFF).toFloat()
            weight[i] = 1f
        }

        val level = Level(width, height, red, green, blue, weight)
        solve(level)

        val grain = grainOf(pixels, width, height, mask)
        for (i in 0 until size) {
            if (!mask[i]) continue
            val alpha = pixels[i] and -0x1000000
            val noise = grain?.at(i) ?: 0f
            pixels[i] = alpha or
                ((level.red[i] + noise).toInt().coerceIn(0, 255) shl 16) or
                ((level.green[i] + noise).toInt().coerceIn(0, 255) shl 8) or
                (level.blue[i] + noise).toInt().coerceIn(0, 255)
        }
    }

    /**
     * The texture the solve cannot reproduce, taken from the pixels around the hole.
     *
     * The push-pull solve answers with a smooth field, which is exactly right on paper and exactly
     * wrong on anything grainy. A "REC" caption over a noisy black panel came back as a smooth grey
     * blob with a ragged edge sitting in the middle of the grain — the erase was visible from across
     * the room even though every pixel of it was the correct average colour.
     *
     * So the grain is carried over rather than invented: the residual of each known pixel against
     * its own neighbourhood is collected, and masked pixels draw from that pool. Where the
     * surroundings are flat the residuals are ~0 and this changes nothing, which is why it can run
     * unconditionally.
     */
    private class Grain(private val samples: FloatArray) {
        fun at(index: Int): Float {
            // Deterministic scatter: the same hole always reconstructs identically, which the
            // regression suite depends on.
            val h = (index * -0x61c88647) ushr 8
            return samples[(h % samples.size)]
        }
    }

    private fun grainOf(pixels: IntArray, width: Int, height: Int, mask: BooleanArray): Grain? {
        if (width < 3 || height < 3) return null
        val samples = ArrayList<Float>()
        var total = 0f
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val i = y * width + x
                if (!mask[i] && !mask[i - 1] && !mask[i + 1] && !mask[i - width] && !mask[i + width]) {
                    val here = luma(pixels[i])
                    val around = (luma(pixels[i - 1]) + luma(pixels[i + 1]) +
                        luma(pixels[i - width]) + luma(pixels[i + width])) / 4f
                    val residual = here - around
                    samples.add(residual)
                    total += kotlin.math.abs(residual)
                }
                x++
            }
            y++
        }
        if (samples.size < MIN_GRAIN_SAMPLES) return null
        if (total / samples.size < MIN_GRAIN_STRENGTH) return null
        return Grain(samples.toFloatArray())
    }

    private fun luma(color: Int): Float =
        0.299f * ((color shr 16) and 0xFF) + 0.587f * ((color shr 8) and 0xFF) + 0.114f * (color and 0xFF)

    private class Level(
        val width: Int,
        val height: Int,
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray,
        val weight: FloatArray,
    )

    private fun solve(level: Level) {
        if (level.width <= PYRAMID_MIN_SIDE || level.height <= PYRAMID_MIN_SIDE) {
            fillFromAverage(level)
            return
        }

        val coarse = downsample(level)
        solve(coarse)
        upsampleInto(coarse, level)
        relax(level)
    }

    /** Terminal case: whatever is still unknown at 2x2 takes the mean of the known samples. */
    private fun fillFromAverage(level: Level) {
        var r = 0f
        var g = 0f
        var b = 0f
        var w = 0f
        for (i in level.weight.indices) {
            val weight = level.weight[i]
            if (weight <= EPSILON) continue
            r += level.red[i]
            g += level.green[i]
            b += level.blue[i]
            w += 1f
        }
        if (w <= 0f) {
            // Nothing known at all — a fully masked region. Mid grey is the least wrong answer, and in
            // practice callers reject a mask this large before reaching here.
            r = 128f
            g = 128f
            b = 128f
            w = 1f
        } else {
            r /= w
            g /= w
            b /= w
        }
        for (i in level.weight.indices) {
            if (level.weight[i] > EPSILON) continue
            level.red[i] = r
            level.green[i] = g
            level.blue[i] = b
            level.weight[i] = 1f
        }
    }

    /** Weighted 2x2 box reduction: masked pixels contribute nothing, so colour never bleeds out of them. */
    private fun downsample(level: Level): Level {
        val width = max(1, level.width / 2)
        val height = max(1, level.height / 2)
        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)
        val weight = FloatArray(size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                var w = 0f
                for (dy in 0..1) {
                    val sy = y * 2 + dy
                    if (sy >= level.height) continue
                    for (dx in 0..1) {
                        val sx = x * 2 + dx
                        if (sx >= level.width) continue
                        val index = sy * level.width + sx
                        val sampleWeight = level.weight[index]
                        if (sampleWeight <= EPSILON) continue
                        r += level.red[index] * sampleWeight
                        g += level.green[index] * sampleWeight
                        b += level.blue[index] * sampleWeight
                        w += sampleWeight
                    }
                }
                val target = y * width + x
                if (w > EPSILON) {
                    red[target] = r / w
                    green[target] = g / w
                    blue[target] = b / w
                    weight[target] = 1f
                }
            }
        }
        return Level(width, height, red, green, blue, weight)
    }

    /** Copies the coarse solution into the unknown pixels of the finer level. */
    private fun upsampleInto(coarse: Level, fine: Level) {
        for (y in 0 until fine.height) {
            val cy = (y / 2).coerceAtMost(coarse.height - 1)
            for (x in 0 until fine.width) {
                val index = y * fine.width + x
                if (fine.weight[index] > EPSILON) continue
                val cx = (x / 2).coerceAtMost(coarse.width - 1)
                val source = cy * coarse.width + cx
                fine.red[index] = coarse.red[source]
                fine.green[index] = coarse.green[source]
                fine.blue[index] = coarse.blue[source]
            }
        }
    }

    /**
     * Jacobi relaxation over the unknown pixels only.
     *
     * Known pixels are never modified, so the reconstruction is seamless at the mask boundary — that
     * boundary is precisely where a flat fill announces itself with a hard edge.
     */
    private fun relax(level: Level) {
        val width = level.width
        val height = level.height
        val red = level.red
        val green = level.green
        val blue = level.blue
        val weight = level.weight

        val nextRed = red.copyOf()
        val nextGreen = green.copyOf()
        val nextBlue = blue.copyOf()

        repeat(RELAX_ITERATIONS) {
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    val index = base + x
                    if (weight[index] > EPSILON) continue

                    var r = 0f
                    var g = 0f
                    var b = 0f
                    var count = 0

                    if (x > 0) {
                        val n = index - 1
                        r += red[n]; g += green[n]; b += blue[n]; count++
                    }
                    if (x < width - 1) {
                        val n = index + 1
                        r += red[n]; g += green[n]; b += blue[n]; count++
                    }
                    if (y > 0) {
                        val n = index - width
                        r += red[n]; g += green[n]; b += blue[n]; count++
                    }
                    if (y < height - 1) {
                        val n = index + width
                        r += red[n]; g += green[n]; b += blue[n]; count++
                    }

                    if (count > 0) {
                        nextRed[index] = r / count
                        nextGreen[index] = g / count
                        nextBlue[index] = b / count
                    }
                }
            }
            for (i in red.indices) {
                if (weight[i] > EPSILON) continue
                red[i] = nextRed[i]
                green[i] = nextGreen[i]
                blue[i] = nextBlue[i]
            }
        }
    }
}
