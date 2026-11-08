package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.repository.PeakingSensitivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.stream.IntStream

/**
 * Focus peaking (R6 D8), the reference for both the CPU path and the AGSL shader. Ported from
 * RapidReady's WebGL overlay, which follows darktable: a two-scale gradient on Rec. 709 luma, where
 * the gradient one pixel apart minus most of the gradient two pixels apart keeps fine detail and
 * drops soft, wide transitions. A 3×3 range gate stops noise in flat areas, and the result is
 * dilated by the four direct neighbours before the threshold.
 *
 * Input is ARGB pixels (sRGB, as drawn); reads past the border repeat the edge pixel, like a
 * clamped texture. Pure: no Android types.
 */
object PeakingKernel {
    /** RapidReady's three presets; a lower threshold marks more. */
    const val THRESHOLD_LOW = 0.070f
    const val THRESHOLD_MEDIUM = 0.040f
    const val THRESHOLD_HIGH = 0.020f

    /** A 3×3 neighbourhood with less luma range than this is treated as flat. */
    const val NOISE_GATE = 0.012f

    /** darktable's share of the two-pixel gradient that is taken away, and its noise floor. */
    const val FAR_WEIGHT = 0.67f
    const val FAR_FLOOR = 0.004f

    fun threshold(sensitivity: PeakingSensitivity): Float = when (sensitivity) {
        PeakingSensitivity.LOW -> THRESHOLD_LOW
        PeakingSensitivity.MEDIUM -> THRESHOLD_MEDIUM
        PeakingSensitivity.HIGH -> THRESHOLD_HIGH
    }

    /** Rec. 709 luma of an ARGB pixel, 0..1, on the sRGB-encoded values (as the shader sees them). */
    fun luma(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /**
     * Which pixels are in focus: true where the dilated focus value is above [threshold].
     *
     * @param checkCancelled called once per row, so a caller can stop a long run; it may be called
     *   from several threads when [bands] > 1.
     * @param bands how many horizontal bands to work on in parallel.
     */
    fun mask(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Float,
        checkCancelled: () -> Unit = {},
        bands: Int = 1,
    ): BooleanArray {
        require(width >= 0 && height >= 0 && pixels.size >= width * height) { "pixels don't cover $width × $height" }
        val out = BooleanArray(width * height)
        if (width == 0 || height == 0) return out
        val padded = paddedLuma(pixels, width, height)
        val focus = FloatArray(width * height)
        inBands(height, bands) { focusRows(padded, width, rows = it, out = focus, checkCancelled = checkCancelled) }
        checkCancelled()
        inBands(height, bands) { thresholdRows(focus, width, height, threshold, it, out) }
        return out
    }

    /** The undilated focus value of every pixel (0 where the range gate calls it flat). */
    fun focusMap(pixels: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        if (width > 0 && height > 0) focusRows(paddedLuma(pixels, width, height), width, 0 until height, out)
        return out
    }

    /** The farthest neighbour the kernel reads (the two-pixel gradient). */
    const val PAD = 2

    /**
     * Luma with a [PAD]-pixel border that repeats the edge, so the inner loop reads neighbours by
     * fixed offsets instead of clamping every coordinate.
     */
    private fun paddedLuma(pixels: IntArray, width: Int, height: Int): FloatArray {
        val stride = width + 2 * PAD
        val out = FloatArray(stride * (height + 2 * PAD))
        for (py in 0 until height + 2 * PAD) {
            val row = min(max(py - PAD, 0), height - 1) * width
            val dest = py * stride
            for (px in 0 until stride) out[dest + px] = luma(pixels[row + min(max(px - PAD, 0), width - 1)])
        }
        return out
    }

    private fun inBands(height: Int, bands: Int, block: (IntRange) -> Unit) {
        val count = bands.coerceIn(1, height)
        if (count == 1) return block(0 until height)
        val size = (height + count - 1) / count
        IntStream.range(0, count).parallel().forEach { b -> block(b * size until min((b + 1) * size, height)) }
    }

    private fun focusRows(padded: FloatArray, width: Int, rows: IntRange, out: FloatArray, checkCancelled: () -> Unit = {}) {
        val p = width + 2 * PAD
        val p2 = 2 * p
        for (y in rows) {
            checkCancelled()
            var i = (y + PAD) * p + PAD
            var o = y * width
            for (x in 0 until width) {
                val c = padded[i]
                val n = padded[i - p]
                val s = padded[i + p]
                val w = padded[i - 1]
                val e = padded[i + 1]
                val nw = padded[i - p - 1]
                val ne = padded[i - p + 1]
                val sw = padded[i + p - 1]
                val se = padded[i + p + 1]
                val lo = lesser(c, lesser(lesser(w, e), lesser(lesser(n, s), lesser(lesser(nw, ne), lesser(sw, se)))))
                val hi = greater(c, greater(greater(w, e), greater(greater(n, s), greater(greater(nw, ne), greater(sw, se)))))
                var value = 0f
                if (hi - lo >= NOISE_GATE) {
                    val close = gradient(n, s, w, e, nw, ne, sw, se)
                    val far = gradient(
                        padded[i - p2], padded[i + p2], padded[i - 2], padded[i + 2],
                        padded[i - p2 - 2], padded[i - p2 + 2], padded[i + p2 - 2], padded[i + p2 + 2],
                    )
                    val excess = far - FAR_FLOOR
                    val bandpass = if (excess > 0f) close - FAR_WEIGHT * excess else close
                    if (bandpass > 0f) value = bandpass
                }
                out[o] = value
                i++
                o++
            }
        }
    }

    /** Dilates [focus] by its 4 direct neighbours and applies [threshold]. */
    private fun thresholdRows(focus: FloatArray, width: Int, height: Int, threshold: Float, rows: IntRange, out: BooleanArray) {
        for (y in rows) {
            val up = max(y - 1, 0) * width
            val row = y * width
            val down = min(y + 1, height - 1) * width
            for (x in 0 until width) {
                val left = if (x > 0) x - 1 else 0
                val right = if (x < width - 1) x + 1 else x
                out[row + x] = focus[row + x] > threshold || focus[up + x] > threshold || focus[down + x] > threshold ||
                    focus[row + left] > threshold || focus[row + right] > threshold
            }
        }
    }

    /** Mean of the axis and diagonal gradient magnitudes. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun gradient(n: Float, s: Float, w: Float, e: Float, nw: Float, ne: Float, sw: Float, se: Float): Float {
        val ax = e - w
        val ay = s - n
        val dx = se - nw
        val dy = sw - ne
        return (sqrt(ax * ax + ay * ay) + sqrt(dx * dx + dy * dy)) * 0.5f
    }

    // No NaN here, so plain comparisons do; kotlin.math's min/max check for it on every call.
    @Suppress("NOTHING_TO_INLINE")
    private inline fun lesser(a: Float, b: Float): Float = if (a < b) a else b

    @Suppress("NOTHING_TO_INLINE")
    private inline fun greater(a: Float, b: Float): Float = if (a > b) a else b
}
