package dev.shehran.rawcull.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raw per-value pixel counts (R6 D9); how they are drawn is [histogramHeights]'s business. */
class HistogramData(
    val red: IntArray = IntArray(BINS),
    val green: IntArray = IntArray(BINS),
    val blue: IntArray = IntArray(BINS),
    val luma: IntArray = IntArray(BINS),
    val shadowClippingCount: Int = 0,
    val highlightClippingCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramData) return false
        return red.contentEquals(other.red) && green.contentEquals(other.green) &&
            blue.contentEquals(other.blue) && luma.contentEquals(other.luma) &&
            shadowClippingCount == other.shadowClippingCount && highlightClippingCount == other.highlightClippingCount
    }

    override fun hashCode(): Int {
        var result = red.contentHashCode()
        result = 31 * result + green.contentHashCode()
        result = 31 * result + blue.contentHashCode()
        result = 31 * result + luma.contentHashCode()
        return result
    }

    companion object {
        const val BINS = 256
    }
}

object HistogramCalculator {
    /** Luma at or below this counts as clipped shadows. */
    const val SHADOW_LIMIT = 2

    /** Luma at or above this counts as clipped highlights. */
    const val HIGHLIGHT_LIMIT = 253

    /**
     * Counts every pixel of [argb] (row-major, [width] × [height]). Large images are sampled on every second
     * pixel and row, which doesn't change the shape. Pure, so it can be tested without Android.
     */
    fun compute(argb: IntArray, width: Int, height: Int): HistogramData {
        val step = if (width * height > SAMPLE_ABOVE_PIXELS) 2 else 1
        val r = IntArray(HistogramData.BINS)
        val g = IntArray(HistogramData.BINS)
        val b = IntArray(HistogramData.BINS)
        val l = IntArray(HistogramData.BINS)
        var shadows = 0
        var highlights = 0
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val color = argb[y * width + x]
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                // Rec. 709 luminance.
                val lum = ((red * 0.2126f) + (green * 0.7152f) + (blue * 0.0722f)).toInt().coerceIn(0, 255)
                r[red]++
                g[green]++
                b[blue]++
                l[lum]++
                if (lum <= SHADOW_LIMIT) shadows++
                if (lum >= HIGHLIGHT_LIMIT) highlights++
            }
        }
        return HistogramData(r, g, b, l, shadows, highlights)
    }

    suspend fun compute(bitmap: Bitmap): HistogramData = withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        compute(pixels, bitmap.width, bitmap.height)
    }

    private const val SAMPLE_ABOVE_PIXELS = 400 * 300
}
