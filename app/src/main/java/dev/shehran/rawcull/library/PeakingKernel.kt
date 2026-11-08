package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.repository.PeakingSensitivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
     * @param checkCancelled called once per row, so a caller can stop a long run.
     */
    fun mask(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Float,
        checkCancelled: () -> Unit = {},
    ): BooleanArray {
        require(width >= 0 && height >= 0 && pixels.size >= width * height) { "pixels don't cover $width × $height" }
        val out = BooleanArray(width * height)
        if (width == 0 || height == 0) return out
        val luma = FloatArray(width * height) { luma(pixels[it]) }
        val focus = focusMap(luma, width, height, checkCancelled)
        for (y in 0 until height) {
            checkCancelled()
            val up = max(y - 1, 0) * width
            val row = y * width
            val down = min(y + 1, height - 1) * width
            for (x in 0 until width) {
                val left = max(x - 1, 0)
                val right = min(x + 1, width - 1)
                val value = max(
                    max(focus[row + x], focus[up + x]),
                    max(focus[down + x], max(focus[row + left], focus[row + right])),
                )
                out[row + x] = value > threshold
            }
        }
        return out
    }

    /** The undilated focus value of every pixel (0 where the range gate calls it flat). */
    fun focusMap(luma: FloatArray, width: Int, height: Int, checkCancelled: () -> Unit = {}): FloatArray {
        val out = FloatArray(width * height)
        fun at(x: Int, y: Int): Float = luma[min(max(y, 0), height - 1) * width + min(max(x, 0), width - 1)]
        for (y in 0 until height) {
            checkCancelled()
            for (x in 0 until width) {
                val c = at(x, y)
                val n = at(x, y - 1)
                val s = at(x, y + 1)
                val w = at(x - 1, y)
                val e = at(x + 1, y)
                val nw = at(x - 1, y - 1)
                val ne = at(x + 1, y - 1)
                val sw = at(x - 1, y + 1)
                val se = at(x + 1, y + 1)
                val lo = min(c, min(min(w, e), min(min(n, s), min(min(nw, ne), min(sw, se)))))
                val hi = max(c, max(max(w, e), max(max(n, s), max(max(nw, ne), max(sw, se)))))
                if (hi - lo < NOISE_GATE) continue
                val close = gradient(n, s, w, e, nw, ne, sw, se)
                val far = gradient(
                    at(x, y - 2), at(x, y + 2), at(x - 2, y), at(x + 2, y),
                    at(x - 2, y - 2), at(x + 2, y - 2), at(x - 2, y + 2), at(x + 2, y + 2),
                )
                val bandpass = close - FAR_WEIGHT * max(far - FAR_FLOOR, 0f)
                out[y * width + x] = max(bandpass, 0f)
            }
        }
        return out
    }

    /** Mean of the axis and diagonal gradient magnitudes. */
    private fun gradient(n: Float, s: Float, w: Float, e: Float, nw: Float, ne: Float, sw: Float, se: Float): Float {
        val axes = hypot(e - w, s - n)
        val diagonals = hypot(se - nw, sw - ne)
        return (axes + diagonals) * 0.5f
    }

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)
}
