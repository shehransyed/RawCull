package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.shehran.rawcull.library.PeakingKernel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R6.6: the CPU peaking path, against real drawing (Robolectric's native graphics). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeakingOverlayTest {
    private fun flat(width: Int, height: Int, gray: Int): Bitmap =
        Bitmap.createBitmap(IntArray(width * height) { 0xFF000000.toInt() or (gray * 0x010101) }, width, height, Bitmap.Config.ARGB_8888)

    /** Dark left half, light right half. */
    private fun edge(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(
            IntArray(width * height) { if (it % width < width / 2) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() },
            width, height, Bitmap.Config.ARGB_8888,
        )

    private fun PeakingMask<*>.markedColumns(width: Int): Set<Int> =
        marked.indices.filter { marked[it] }.map { it % width }.toSet()

    @Test
    fun `the mask follows the 100 percent tile`() {
        // A flat 50 px preview stretched over a 200 px view, and a 40 px tile at (100, 60) of the
        // same grey with a sharp dark line in columns 18..21 that the preview was too small to show.
        val base = flat(50, 50, 128)
        val baseMatrix = Matrix().apply { setScale(4f, 4f) }
        val tile = Bitmap.createBitmap(
            IntArray(40 * 40) { if (it % 40 in 18..21) 0xFF202020.toInt() else 0xFF808080.toInt() },
            40, 40, Bitmap.Config.ARGB_8888,
        )
        val tileMatrix = Matrix().apply { setTranslate(100f, 60f) }

        val without = renderPeakingMask("fit", base, baseMatrix, null, null, 200, 200, PeakingKernel.THRESHOLD_MEDIUM)
        assertNotNull(without)
        assertFalse(without!!.marked.any { it })

        val with = renderPeakingMask("tile", base, baseMatrix, tile, tileMatrix, 200, 200, PeakingKernel.THRESHOLD_MEDIUM)!!
        assertEquals(200, with.bitmap.width)
        val marked = with.marked.indices.filter { with.marked[it] }
        assertTrue(marked.isNotEmpty())
        // Only around the line (x = 118..121) and only within the tile's rows (60..99).
        assertTrue(marked.all { (it % 200) in 115..124 })
        assertTrue(marked.all { (it / 200) in 58..101 })
    }

    @Test
    fun `only the photo's rectangle is read, so its border isn't an edge`() {
        // A flat photo letterboxed in a larger, transparent view.
        val base = flat(100, 50, 200)
        val matrix = Matrix().apply { setTranslate(50f, 75f) }
        val mask = renderPeakingMask("p", base, matrix, null, null, 200, 200, PeakingKernel.THRESHOLD_HIGH)!!
        assertEquals(100, mask.bitmap.width)
        assertEquals(50f, mask.dest.left)
        assertEquals(75f, mask.dest.top)
        assertFalse(mask.marked.any { it })
    }

    @Test
    fun `a large view is computed at no more than the long edge limit and drawn full size`() {
        val base = edge(64, 40)
        val matrix = Matrix().apply { setScale(80f, 80f) } // 5120 × 3200 on screen
        val mask = renderPeakingMask("big", base, matrix, null, null, 5120, 3200, PeakingKernel.THRESHOLD_MEDIUM)!!
        assertEquals(PEAKING_MAX_EDGE, mask.bitmap.width)
        assertEquals(5120f, mask.dest.width(), 1f)
        assertTrue(mask.markedColumns(PEAKING_MAX_EDGE).all { it in (PEAKING_MAX_EDGE / 2 - 3)..(PEAKING_MAX_EDGE / 2 + 2) })
    }

    @Test
    fun `a new photo cancels the mask in flight and only the newest result shows`() {
        val scope = TestScope(StandardTestDispatcher())
        val gates = mapOf("a" to CompletableDeferred<String>(), "b" to CompletableDeferred<String>())
        val cancelled = mutableListOf<String>()
        val runner = LatestJob<String, String>(scope as CoroutineScope) { key ->
            try {
                gates.getValue(key).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += key
                throw e
            }
        }

        runner.request("a")
        scope.advanceUntilIdle()
        runner.request("b")
        scope.advanceUntilIdle()
        assertEquals(listOf("a"), cancelled)
        gates.getValue("a").complete("mask a")
        assertNull(runner.resultFor("b"))

        gates.getValue("b").complete("mask b")
        scope.advanceUntilIdle()
        assertEquals("mask b", runner.resultFor("b"))
        assertNull(runner.resultFor("a"))

        // Turning peaking off and on again for the same photo reuses the mask.
        runner.request(null)
        assertNull(runner.resultFor(null))
        runner.request("b")
        assertEquals("mask b", runner.resultFor("b"))
    }
}
