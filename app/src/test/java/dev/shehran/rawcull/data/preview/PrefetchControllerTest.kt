package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.preview.PrefetchWindows
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchWindowsTest {
    @Test
    fun `grid takes three rows each way, nearest first, below before above`() {
        // 4 columns, rows 5 and 6 visible (indices 20..27) of 100.
        val window = PrefetchWindows.grid(firstVisible = 20, lastVisible = 27, columns = 4, count = 100)
        assertEquals(24, window.size)
        assertEquals(listOf(28, 19, 29, 18), window.take(4))
        assertEquals((28..39).toSet() + (8..19).toSet(), window.toSet())
    }

    @Test
    fun `grid stops at both ends`() {
        val window = PrefetchWindows.grid(firstVisible = 0, lastVisible = 5, columns = 3, count = 8)
        assertEquals(listOf(6, 7), window)
    }

    @Test
    fun `grid with nothing visible asks for nothing`() {
        assertTrue(PrefetchWindows.grid(firstVisible = -1, lastVisible = -1, columns = 3, count = 8).isEmpty())
        assertTrue(PrefetchWindows.grid(firstVisible = 0, lastVisible = 0, columns = 3, count = 0).isEmpty())
    }

    @Test
    fun `loupe looks five ahead and one behind on wide windows`() {
        assertEquals(listOf(11, 12, 13, 14, 15, 9), PrefetchWindows.loupe(current = 10, direction = 1, count = 100, compact = false))
    }

    @Test
    fun `loupe looks three ahead on compact windows`() {
        assertEquals(listOf(11, 12, 13, 9), PrefetchWindows.loupe(current = 10, direction = 1, count = 100, compact = true))
    }

    @Test
    fun `going backwards looks the other way`() {
        assertEquals(listOf(9, 8, 7, 11), PrefetchWindows.loupe(current = 10, direction = -1, count = 100, compact = true))
    }

    @Test
    fun `loupe stays inside the list`() {
        assertEquals(listOf(1), PrefetchWindows.loupe(current = 0, direction = -1, count = 3, compact = false))
        assertTrue(PrefetchWindows.loupe(current = 5, direction = 1, count = 3, compact = false).isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchControllerTest {
    private fun source(i: Int) = PreviewSource("content://photo/$i", PhotoFormat.ARW, null, "k$i")

    private fun TestScope.controller(repository: FakePreviewRepository) = PrefetchController(repository, backgroundScope)

    private fun List<PrefetchRequest>.indices() = map { it.source.key.removePrefix("k").toInt() }

    @Test
    fun `a scroll asks once it settles, for where it ended`() = runTest {
        val repository = FakePreviewRepository()
        val controller = controller(repository)
        runCurrent()
        for (first in 0..40 step 4) {
            controller.grid(first, first + 11, columns = 4, count = 200, sizePx = 512, source = ::source)
            advanceTimeBy(30)
        }
        assertTrue(repository.prefetches.isEmpty())
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)

        assertEquals(1, repository.prefetches.size)
        val (lane, requests) = repository.prefetches.single()
        assertEquals(PreviewLane.PREFETCH, lane)
        assertEquals(PrefetchWindows.grid(40, 51, 4, 200), requests.indices())
        assertTrue(requests.all { it is PrefetchRequest.Thumbnail && it.sizePx == 512 })
    }

    @Test
    fun `each settled window replaces the last one`() = runTest {
        val repository = FakePreviewRepository()
        val controller = controller(repository)
        runCurrent()
        controller.grid(0, 11, columns = 4, count = 200, sizePx = 256, source = ::source)
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)
        controller.grid(100, 111, columns = 4, count = 200, sizePx = 256, source = ::source)
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)

        assertEquals(2, repository.prefetches.size)
        // The repository cancels what the new list leaves out; the old window shares nothing with it.
        val (old, new) = repository.prefetches.map { it.second.indices().toSet() }
        assertTrue(old.intersect(new).isEmpty())
    }

    @Test
    fun `the loupe follows the direction of the last move`() = runTest {
        val repository = FakePreviewRepository()
        val controller = controller(repository)
        runCurrent()
        fun at(index: Int) = controller.loupe(index, count = 100, compact = true, widthPx = 1000, heightPx = 800, source = ::source)

        at(10)
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)
        at(11)
        at(12) // A held key: only where it stops counts
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)
        at(11)
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)
        at(11) // Staying put keeps the direction
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)

        val windows = repository.prefetches.map { it.second.indices() }
        assertEquals(
            listOf(
                listOf(11, 12, 13, 9),
                listOf(13, 14, 15, 11),
                listOf(10, 9, 8, 12),
            ),
            windows.take(3),
        )
        assertTrue(repository.prefetches.flatMap { it.second }.all { it is PrefetchRequest.Fit && it.widthPx == 1000 && it.heightPx == 800 })
    }

    @Test
    fun `a filmstrip jump back counts as going back`() = runTest {
        val repository = FakePreviewRepository()
        val controller = controller(repository)
        runCurrent()
        controller.loupe(50, count = 100, compact = false, widthPx = 10, heightPx = 10, source = ::source)
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)
        controller.loupe(20, count = 100, compact = false, widthPx = 10, heightPx = 10, source = ::source)
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)

        assertEquals(listOf(19, 18, 17, 16, 15, 21), repository.prefetches.last().second.indices())
    }

    @Test
    fun `going idle cancels the prefetch lane`() = runTest {
        val repository = FakePreviewRepository()
        val controller = controller(repository)
        runCurrent()
        controller.grid(0, 11, columns = 4, count = 200, sizePx = 256, source = ::source)
        controller.idle()
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)

        assertTrue(repository.prefetches.isEmpty())
        assertEquals(listOf(PreviewLane.PREFETCH), repository.cancelled)
    }

    @Test
    fun `sources are only worked out for the window`() = runTest {
        val repository = FakePreviewRepository()
        val controller = controller(repository)
        runCurrent()
        val asked = mutableListOf<Int>()
        controller.grid(0, 11, columns = 4, count = 10_000, sizePx = 256) { asked += it; source(it) }
        advanceTimeBy(PREFETCH_DEBOUNCE_MS + 1)

        assertEquals(12, asked.size)
    }
}
