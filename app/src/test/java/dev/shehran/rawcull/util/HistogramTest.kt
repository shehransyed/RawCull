package dev.shehran.rawcull.util

import dev.shehran.rawcull.data.repository.HistogramMode
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {
    private fun solid(rgb: Int, w: Int = 10, h: Int = 10) = IntArray(w * h) { 0xFF000000.toInt() or rgb }

    @Test
    fun `an all-black image puts every pixel in bin 0 and clips the shadows`() {
        val data = HistogramCalculator.compute(solid(0x000000), 10, 10)
        listOf(data.red, data.green, data.blue, data.luma).forEach {
            assertEquals(100, it[0])
            assertEquals(100, it.sum())
        }
        assertEquals(100, data.shadowClippingCount)
        assertEquals(0, data.highlightClippingCount)
    }

    @Test
    fun `an all-white image puts every pixel in bin 255 and clips the highlights`() {
        val data = HistogramCalculator.compute(solid(0xFFFFFF), 10, 10)
        assertEquals(100, data.red[255])
        assertEquals(100, data.luma[255])
        assertEquals(0, data.shadowClippingCount)
        assertEquals(100, data.highlightClippingCount)
    }

    @Test
    fun `a two-tone image counts each half and uses Rec 709 luma`() {
        val pixels = IntArray(100) { if (it < 40) 0xFF000000.toInt() or 0xFF0000 else 0xFF000000.toInt() or 0x808080 }
        val data = HistogramCalculator.compute(pixels, 10, 10)
        assertEquals(40, data.red[255])
        assertEquals(60, data.red[128])
        assertEquals(40, data.green[0])
        assertEquals(40, data.luma[(255 * 0.2126f).toInt()])
        assertEquals(60, data.luma[128])
    }

    @Test
    fun `large images are sampled but keep their shape`() {
        val data = HistogramCalculator.compute(solid(0x808080, 800, 600), 800, 600)
        assertEquals(400 * 300, data.red[128])
    }

    private fun spiky(): HistogramData {
        val counts = IntArray(256)
        counts[0] = 10_000 // a clipped spike, which must not set the scale
        counts[100] = 100
        counts[101] = 10
        counts[255] = 5_000
        return HistogramData(counts, counts.copyOf(), counts.copyOf(), counts.copyOf())
    }

    @Test
    fun `linear heights are relative to the tallest bar between 1 and 254`() {
        val luma = histogramHeights(spiky(), HistogramMode.RGB_LUMA, log = false).luma!!
        assertEquals(1f, luma[100], 1e-6f)
        assertEquals(0.1f, luma[101], 1e-6f)
        assertEquals(1f, luma[0], 0f) // capped
        assertEquals(1f, luma[255], 0f)
        assertEquals(0f, luma[50], 0f)
    }

    @Test
    fun `log lifts small counts but keeps the order`() {
        val linear = histogramHeights(spiky(), HistogramMode.LUMA, log = false).luma!!
        val log = histogramHeights(spiky(), HistogramMode.LUMA, log = true).luma!!
        assertEquals(1f, log[100], 1e-6f)
        assertTrue(log[101] > linear[101])
        assertTrue(log[101] < log[100])
        assertEquals(0f, log[50], 0f)
        assertEquals(ln(11f) / ln(101f), log[101], 1e-5f)
    }

    @Test
    fun `modes pick the channels`() {
        val data = spiky()
        histogramHeights(data, HistogramMode.RGB_LUMA, false).let {
            assertNotNull(it.red); assertNotNull(it.green); assertNotNull(it.blue); assertNotNull(it.luma)
        }
        histogramHeights(data, HistogramMode.RGB, false).let {
            assertNotNull(it.red); assertNotNull(it.blue); assertNull(it.luma)
        }
        histogramHeights(data, HistogramMode.LUMA, false).let {
            assertNull(it.red); assertNull(it.green); assertNull(it.blue); assertNotNull(it.luma)
        }
    }

    @Test
    fun `the scale follows the channels that are drawn`() {
        val luma = IntArray(256).also { it[10] = 50 }
        val red = IntArray(256).also { it[10] = 500 }
        val data = HistogramData(red, IntArray(256), IntArray(256), luma)
        assertEquals(1f, histogramHeights(data, HistogramMode.LUMA, false).luma!![10], 1e-6f)
        assertEquals(0.1f, histogramHeights(data, HistogramMode.RGB_LUMA, false).luma!![10], 1e-6f)
    }

    @Test
    fun `an empty histogram gives flat heights`() {
        val h = histogramHeights(HistogramData(), HistogramMode.RGB_LUMA, log = true)
        assertTrue(h.luma!!.all { it == 0f })
    }

    @Test
    fun `modes cycle through all three`() {
        assertEquals(HistogramMode.RGB, HistogramMode.RGB_LUMA.next())
        assertEquals(HistogramMode.LUMA, HistogramMode.RGB.next())
        assertEquals(HistogramMode.RGB_LUMA, HistogramMode.LUMA.next())
    }
}
