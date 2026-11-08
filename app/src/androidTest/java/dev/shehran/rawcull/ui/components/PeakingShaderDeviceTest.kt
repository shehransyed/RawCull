package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Picture
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.shehran.rawcull.library.PeakingKernel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.exp
import kotlin.random.Random

/**
 * R6.7: the AGSL peaking shader against [PeakingKernel], rendered off-screen on the device's GPU
 * (a `Picture` turned into a hardware bitmap), on a synthetic image. Self-contained: no SAF, no
 * pickers. API 33+; skipped below.
 *
 * Tolerance: at most 1 % of the interior pixels may differ (the shader works in half precision,
 * so values right at the threshold can land either way), and the shader must find at least 95 %
 * of the kernel's marks. A 3 px border is left out: the shader clamps at the bitmap's edge, which
 * matches the kernel there only approximately once the dilation reaches past it.
 */
@RunWith(AndroidJUnit4::class)
class PeakingShaderDeviceTest {
    @Before
    fun needsAgsl() {
        assumeTrue("RuntimeShader needs API 33", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }

    @Test
    fun shaderMarksTheSamePixelsAsTheKernel() {
        val width = 192
        val height = 128
        val pixels = syntheticImage(width, height)
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

        for (threshold in listOf(PeakingKernel.THRESHOLD_LOW, PeakingKernel.THRESHOLD_MEDIUM, PeakingKernel.THRESHOLD_HIGH)) {
            val expected = PeakingKernel.mask(pixels, width, height, threshold)
            val drawn = renderShader(source, Matrix(), width, height, threshold)
            val actual = BooleanArray(width * height) { Color.alpha(drawn[it]) > 127 }

            var interior = 0
            var differ = 0
            var kernelMarks = 0
            var found = 0
            for (y in BORDER until height - BORDER) for (x in BORDER until width - BORDER) {
                val i = y * width + x
                interior++
                if (expected[i] != actual[i]) differ++
                if (expected[i]) {
                    kernelMarks++
                    if (actual[i]) found++
                }
            }
            Log.i(TAG, "threshold $threshold: kernel $kernelMarks marks, shader found $found, $differ of $interior differ")
            assertTrue("the kernel marks something at $threshold", kernelMarks > 50)
            assertTrue("$differ of $interior pixels differ at $threshold", differ <= interior / 100)
            assertTrue("shader found $found of $kernelMarks at $threshold", found >= kernelMarks * 95 / 100)
        }
    }

    /** D13, reported, not gated: the shader's cost per frame and the CPU mask at 2560 × 1600. */
    @Test
    fun reportTimings() {
        val width = 2560
        val height = 1600
        val random = Random(3)
        val preview = Bitmap.createBitmap(
            IntArray(1280 * 800) { 0xFF000000.toInt() or (random.nextInt(0, 256) * 0x010101) }, 1280, 800, Bitmap.Config.ARGB_8888,
        )
        val matrix = Matrix().apply { setScale(2f, 2f) }

        val cpu = (1..3).map {
            val start = SystemClock.elapsedRealtimeNanos()
            val bands = Runtime.getRuntime().availableProcessors()
            assertNotNull(renderPeakingMask(it, preview, matrix, null, null, width, height, PeakingKernel.THRESHOLD_MEDIUM, bands = bands))
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }.sorted()[1]

        val shader = checkNotNull(PeakingShader.get()) { "the AGSL shader didn't compile" }
        fun frames(withShader: Boolean): Double {
            renderPicture(width, height) { if (withShader) drawShader(it, shader, preview, matrix, width, height, PeakingKernel.THRESHOLD_MEDIUM) else it.drawBitmap(preview, matrix, null) }
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(FRAMES) {
                renderPicture(width, height) { canvas ->
                    canvas.drawBitmap(preview, matrix, null)
                    if (withShader) drawShader(canvas, shader, preview, matrix, width, height, PeakingKernel.THRESHOLD_MEDIUM)
                }
            }
            return (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / FRAMES
        }
        val plain = frames(false)
        val peaking = frames(true)
        Log.i(TAG, "D13 on ${Build.MODEL} (API ${Build.VERSION.SDK_INT}): CPU mask %.0f ms for $width × $height (${Runtime.getRuntime().availableProcessors()} cores, a debuggable build); frame %.1f ms plain, %.1f ms with the shader (+%.1f ms)"
            .format(cpu, plain, peaking, peaking - plain))
    }

    private fun renderShader(source: Bitmap, matrix: Matrix, width: Int, height: Int, threshold: Float): IntArray {
        val shader = checkNotNull(PeakingShader.get()) { "the AGSL shader didn't compile" }
        val bitmap = renderPicture(width, height) { drawShader(it, shader, source, matrix, width, height, threshold) }
        return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
    }

    private fun drawShader(canvas: android.graphics.Canvas, shader: PeakingShader, source: Bitmap, matrix: Matrix, width: Int, height: Int, threshold: Float) {
        shader.draw(canvas, source, matrix, RectF(0f, 0f, width.toFloat(), height.toFloat()), Color.RED, threshold)
    }

    /** Records [draw], renders it on the GPU into a hardware bitmap and reads it back. */
    private fun renderPicture(width: Int, height: Int, draw: (android.graphics.Canvas) -> Unit): Bitmap {
        val picture = Picture()
        draw(picture.beginRecording(width, height))
        picture.endRecording()
        val hardware = Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE)
        return hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
    }

    /** Hard edges, a soft edge, a diagonal line, texture and a noisy flat area, side by side. */
    private fun syntheticImage(width: Int, height: Int): IntArray {
        val random = Random(11)
        fun gray(v: Int) = 0xFF000000.toInt() or (v.coerceIn(0, 255) * 0x010101)
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            when {
                x < 48 -> gray(if (x < 24) 40 else 210) // hard vertical edge
                x < 96 -> gray((40 + 170 / (1 + exp(-(x - 72) / 3.0))).toInt()) // soft edge
                x < 144 -> gray(if (kotlin.math.abs((x - 96) - y / 2) <= 1) 30 else 190) // diagonal line
                y < height / 2 -> gray(110 + ((x / 2 + y / 3) % 2) * random.nextInt(10, 70)) // texture
                else -> gray(128 + random.nextInt(-1, 2)) // flat with faint noise
            }
        }
    }

    private companion object {
        const val TAG = "PeakingShaderDeviceTest"
        const val BORDER = 3
        const val FRAMES = 20
    }
}
