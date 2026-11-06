package dev.shehran.rawcull.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistogramData(
    val redBins: FloatArray = FloatArray(256),
    val greenBins: FloatArray = FloatArray(256),
    val blueBins: FloatArray = FloatArray(256),
    val lumBins: FloatArray = FloatArray(256),
    val maxBinValue: Float = 1f,
    val shadowClippingCount: Int = 0,
    val highlightClippingCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HistogramData
        return redBins.contentEquals(other.redBins) &&
                greenBins.contentEquals(other.greenBins) &&
                blueBins.contentEquals(other.blueBins) &&
                lumBins.contentEquals(other.lumBins)
    }

    override fun hashCode(): Int {
        var result = redBins.contentHashCode()
        result = 31 * result + greenBins.contentHashCode()
        result = 31 * result + blueBins.contentHashCode()
        result = 31 * result + lumBins.contentHashCode()
        return result
    }
}

object HistogramCalculator {
    suspend fun compute(bitmap: Bitmap): HistogramData = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        // Downsample for lightning-fast histogram computation if large
        val step = if (width * height > 400 * 300) 2 else 1

        val rBins = IntArray(256)
        val gBins = IntArray(256)
        val bBins = IntArray(256)
        val lBins = IntArray(256)

        var shadowsClipped = 0
        var highlightsClipped = 0

        val pixels = IntArray(width)

        for (y in 0 until height step step) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width step step) {
                val color = pixels[x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // Rec. 709 luminance formula
                val lum = ((r * 0.2126f) + (g * 0.7152f) + (b * 0.0722f)).toInt().coerceIn(0, 255)

                rBins[r]++
                gBins[g]++
                bBins[b]++
                lBins[lum]++

                if (lum <= 2) shadowsClipped++
                if (lum >= 253) highlightsClipped++
            }
        }

        // Find max peak for normalization (ignoring extreme 0 and 255 spike if any)
        var maxPeak = 1
        for (i in 1..254) {
            if (rBins[i] > maxPeak) maxPeak = rBins[i]
            if (gBins[i] > maxPeak) maxPeak = gBins[i]
            if (bBins[i] > maxPeak) maxPeak = bBins[i]
            if (lBins[i] > maxPeak) maxPeak = lBins[i]
        }

        val normR = FloatArray(256) { (rBins[it].toFloat() / maxPeak).coerceAtMost(1f) }
        val normG = FloatArray(256) { (gBins[it].toFloat() / maxPeak).coerceAtMost(1f) }
        val normB = FloatArray(256) { (bBins[it].toFloat() / maxPeak).coerceAtMost(1f) }
        val normL = FloatArray(256) { (lBins[it].toFloat() / maxPeak).coerceAtMost(1f) }

        HistogramData(
            redBins = normR,
            greenBins = normG,
            blueBins = normB,
            lumBins = normL,
            maxBinValue = 1f,
            shadowClippingCount = shadowsClipped,
            highlightClippingCount = highlightsClipped
        )
    }
}
