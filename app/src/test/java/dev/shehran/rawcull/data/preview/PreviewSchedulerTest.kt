package dev.shehran.rawcull.data.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSchedulerTest {
    private fun TestScope.scheduler(maxRunning: Int) = PreviewScheduler(backgroundScope, maxRunning)

    @Test
    fun `identical requests share one task`() = runTest {
        val scheduler = scheduler(4)
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val first = async { scheduler.run("a", PreviewLane.VISIBLE) { runs++; gate.await(); "done" } }
        val second = async { scheduler.run("a", PreviewLane.PREFETCH) { runs++; "other" } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("done", first.await())
        assertEquals("done", second.await())
        assertEquals(1, runs)
    }

    @Test
    fun `lanes run most urgent first, oldest first within a lane`() = runTest {
        val scheduler = scheduler(1)
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        scheduler.submit("blocker", PreviewLane.WARMUP) { gate.await() }
        runCurrent()
        scheduler.submit("warm", PreviewLane.WARMUP) { order += "warm" }
        scheduler.submit("pre1", PreviewLane.PREFETCH) { order += "pre1" }
        scheduler.submit("pre2", PreviewLane.PREFETCH) { order += "pre2" }
        val loupe = async { scheduler.run("loupe", PreviewLane.LOUPE_CURRENT) { order += "loupe" } }
        val visible = async { scheduler.run("visible", PreviewLane.VISIBLE) { order += "visible" } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        loupe.await()
        visible.await()
        assertEquals(listOf("visible", "loupe", "pre1", "pre2", "warm"), order)
    }

    @Test
    fun `no more than the limit run at once`() = runTest {
        val scheduler = scheduler(2)
        var running = 0
        var peak = 0
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..6).map { i ->
            async {
                scheduler.run("$i", PreviewLane.VISIBLE) {
                    running++
                    peak = maxOf(peak, running)
                    gate.await()
                    running--
                }
            }
        }
        runCurrent()
        assertEquals(2, peak)
        gate.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(2, peak)
    }

    @Test
    fun `only one warm-up runs, leaving room for the visible lane`() = runTest {
        val scheduler = scheduler(3)
        var started = 0
        repeat(3) { i -> scheduler.submit("warm$i", PreviewLane.WARMUP) { started++; awaitCancellation() } }
        runCurrent()
        assertEquals(1, started)
        var visibleRan = false
        val visible = async { scheduler.run("v", PreviewLane.VISIBLE) { visibleRan = true } }
        runCurrent()
        visible.await()
        assertTrue(visibleRan)
    }

    @Test
    fun `asking again in a more urgent lane moves a queued task up`() = runTest {
        val scheduler = scheduler(1)
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        scheduler.submit("blocker", PreviewLane.VISIBLE) { gate.await() }
        scheduler.submit("a", PreviewLane.PREFETCH) { order += "a" }
        scheduler.submit("b", PreviewLane.PREFETCH) { order += "b" }
        val promoted = async { scheduler.run("b", PreviewLane.VISIBLE) { order += "never" } }
        runCurrent()
        gate.complete(Unit)
        promoted.await()
        runCurrent()
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun `a task nobody waits for any more is cancelled`() = runTest {
        val scheduler = scheduler(1)
        var cancelled = false
        val waiter = launch {
            scheduler.run("a", PreviewLane.VISIBLE) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        runCurrent()
        waiter.cancel()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test
    fun `one of two waiters leaving doesn't cancel the task`() = runTest {
        val scheduler = scheduler(1)
        val gate = CompletableDeferred<Unit>()
        val leaving = launch { scheduler.run<Int>("a", PreviewLane.VISIBLE) { gate.await(); 1 } }
        val staying = async { scheduler.run("a", PreviewLane.VISIBLE) { 2 } }
        runCurrent()
        leaving.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(1, staying.await())
    }

    @Test
    fun `cancelling a lane spares what's kept and what someone waits for`() = runTest {
        val scheduler = scheduler(1)
        val ran = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        scheduler.submit("blocker", PreviewLane.VISIBLE) { gate.await() }
        scheduler.submit("stale", PreviewLane.PREFETCH) { ran += "stale" }
        scheduler.submit("kept", PreviewLane.PREFETCH) { ran += "kept" }
        scheduler.submit("wanted", PreviewLane.PREFETCH) { ran += "wanted" }
        val waiter = async { scheduler.run("wanted", PreviewLane.PREFETCH) { ran += "never" } }
        runCurrent()

        scheduler.cancel(PreviewLane.PREFETCH, keep = setOf("kept"))
        gate.complete(Unit)
        waiter.await()
        runCurrent()

        assertEquals(setOf("kept", "wanted"), ran.toSet())
    }

    @Test
    fun `a failure reaches the waiter and frees the slot`() = runTest {
        val scheduler = scheduler(1)
        val failed = runCatching { scheduler.run("a", PreviewLane.VISIBLE) { error("bad") } }
        assertTrue(failed.isFailure)
        assertEquals(7, scheduler.run("b", PreviewLane.VISIBLE) { 7 })
    }

    @Test
    fun `foreground busy follows the visible lanes only`() = runTest(UnconfinedTestDispatcher()) {
        val scheduler = scheduler(2)
        val gate = CompletableDeferred<Unit>()
        scheduler.submit("pre", PreviewLane.PREFETCH) { gate.await() }
        assertFalse(scheduler.foregroundBusy.value)
        val visible = async { scheduler.run("v", PreviewLane.VISIBLE) { gate.await() } }
        assertTrue(scheduler.foregroundBusy.value)
        gate.complete(Unit)
        visible.await()
        assertFalse(scheduler.foregroundBusy.value)
    }
}
