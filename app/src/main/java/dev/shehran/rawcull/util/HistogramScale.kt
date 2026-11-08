package dev.shehran.rawcull.util

import dev.shehran.rawcull.data.repository.HistogramMode
import kotlin.math.ln

/** Plot heights, 0..1 per value, for the channels a [HistogramMode] draws; the others are null. */
class HistogramHeights(
    val red: FloatArray?,
    val green: FloatArray?,
    val blue: FloatArray?,
    val luma: FloatArray?,
)

/**
 * R6 D9: turns raw counts into plot heights. The tallest bar among the drawn channels is 1; values 0 and 255
 * don't set that peak (a clipped spike would flatten everything else) and are capped at 1 instead.
 * Linear is `count / peak`; [log] is `ln(1 + count) / ln(1 + peak)`, which lifts the small amounts.
 */
fun histogramHeights(data: HistogramData, mode: HistogramMode, log: Boolean): HistogramHeights {
    val rgb = mode != HistogramMode.LUMA
    val luma = mode != HistogramMode.RGB
    val drawn = buildList {
        if (rgb) { add(data.red); add(data.green); add(data.blue) }
        if (luma) add(data.luma)
    }
    var peak = 1
    for (counts in drawn) for (i in 1..HistogramData.BINS - 2) if (counts[i] > peak) peak = counts[i]
    val divisor = if (log) ln(1f + peak) else peak.toFloat()
    fun heights(counts: IntArray) = FloatArray(HistogramData.BINS) {
        val value = if (log) ln(1f + counts[it]) else counts[it].toFloat()
        (value / divisor).coerceAtMost(1f)
    }
    return HistogramHeights(
        red = if (rgb) heights(data.red) else null,
        green = if (rgb) heights(data.green) else null,
        blue = if (rgb) heights(data.blue) else null,
        luma = if (luma) heights(data.luma) else null,
    )
}
