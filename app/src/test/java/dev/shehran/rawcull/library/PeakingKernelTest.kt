package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.repository.PeakingSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class PeakingKernelTest {
    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun image(width: Int, height: Int, value: (Int, Int) -> Int): IntArray =
        IntArray(width * height) { gray(value(it % width, it / width).coerceIn(0, 255)) }

    private fun BooleanArray.count(width: Int, cols: IntRange, rows: IntRange): Int =
        rows.sumOf { y -> cols.count { x -> this[y * width + x] } }

    private val w = 32
    private val h = 24

    @Test
    fun `a hard edge is marked along its length`() {
        val pixels = image(w, h) { x, _ -> if (x < w / 2) 40 else 210 }
        val mask = PeakingKernel.mask(pixels, w, h, PeakingKernel.THRESHOLD_MEDIUM)
        for (y in 0 until h) assertTrue("row $y", mask[y * w + w / 2 - 1] || mask[y * w + w / 2])
        // Far from the edge nothing is marked.
        assertEquals(0, mask.count(w, 0..(w / 2 - 5), 0 until h))
        assertEquals(0, mask.count(w, (w / 2 + 4) until w, 0 until h))
    }

    @Test
    fun `a flat area and a gentle gradient are not marked`() {
        val flat = image(w, h) { _, _ -> 128 }
        assertFalse(PeakingKernel.mask(flat, w, h, PeakingKernel.THRESHOLD_HIGH).any { it })
        val ramp = image(w, h) { x, _ -> 60 + x * 4 }
        assertFalse(PeakingKernel.mask(ramp, w, h, PeakingKernel.THRESHOLD_HIGH).any { it })
    }

    @Test
    fun `a blurred edge is marked less than the sharp one`() {
        fun edge(sigma: Double) = image(w, h) { x, _ -> (40 + 170 / (1 + exp(-(x - w / 2 + 0.5) / sigma))).toInt() }
        val sharp = PeakingKernel.mask(edge(0.3), w, h, PeakingKernel.THRESHOLD_MEDIUM).count { it }
        val soft = PeakingKernel.mask(edge(3.0), w, h, PeakingKernel.THRESHOLD_MEDIUM).count { it }
        assertTrue("sharp $sharp vs soft $soft", sharp > 0 && soft < sharp)
    }

    @Test
    fun `low noise in a flat area stays below the gate`() {
        val random = Random(7)
        val noisy = image(w, h) { _, _ -> 128 + random.nextInt(-1, 2) }
        assertFalse(PeakingKernel.mask(noisy, w, h, PeakingKernel.THRESHOLD_HIGH).any { it })
    }

    @Test
    fun `sensitivities are ordered`() {
        val random = Random(42)
        val textured = image(64, 48) { x, y -> 128 + ((x / 3 + y / 5) % 2) * random.nextInt(4, 60) - random.nextInt(0, 30) }
        val low = PeakingKernel.mask(textured, 64, 48, PeakingKernel.threshold(PeakingSensitivity.LOW))
        val medium = PeakingKernel.mask(textured, 64, 48, PeakingKernel.threshold(PeakingSensitivity.MEDIUM))
        val high = PeakingKernel.mask(textured, 64, 48, PeakingKernel.threshold(PeakingSensitivity.HIGH))
        for (i in low.indices) {
            if (low[i]) assertTrue(medium[i])
            if (medium[i]) assertTrue(high[i])
        }
        assertTrue(low.count { it } < high.count { it })
    }

    @Test
    fun `borders and tiny images don't crash`() {
        for ((iw, ih) in listOf(0 to 0, 1 to 1, 2 to 1, 1 to 5, 3 to 3)) {
            val pixels = image(iw, ih) { x, y -> if ((x + y) % 2 == 0) 0 else 255 }
            assertEquals(iw * ih, PeakingKernel.mask(pixels, iw, ih, PeakingKernel.THRESHOLD_MEDIUM).size)
        }
        // An edge on the image border itself.
        val pixels = image(w, h) { x, _ -> if (x == 0) 255 else 0 }
        assertTrue(PeakingKernel.mask(pixels, w, h, PeakingKernel.THRESHOLD_MEDIUM)[0])
    }

    @Test
    fun `parallel bands give the same mask`() {
        val random = Random(5)
        val pixels = image(97, 61) { _, _ -> random.nextInt(0, 256) }
        val single = PeakingKernel.mask(pixels, 97, 61, PeakingKernel.THRESHOLD_MEDIUM)
        for (bands in listOf(2, 3, 4, 8, 200)) {
            assertTrue("$bands bands", single.contentEquals(PeakingKernel.mask(pixels, 97, 61, PeakingKernel.THRESHOLD_MEDIUM, bands = bands)))
        }
    }

    @Test
    fun `cancelling stops the run`() {
        var rows = 0
        val result = runCatching {
            PeakingKernel.mask(image(w, h) { _, _ -> 0 }, w, h, 0.04f, checkCancelled = { if (++rows > 3) throw IllegalStateException("stop") })
        }
        assertTrue(result.isFailure)
        assertEquals(4, rows)
    }
}
