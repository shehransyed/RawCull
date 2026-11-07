package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.ui.components.warmupText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewWarmupTest {
    private fun source(i: Int) = PreviewSource("content://photo/$i", PhotoFormat.ARW, null, "k$i")

    /** Builds take [buildMillis] of the fake clock; [cached] keys are already on disk. */
    private class Repo(val cached: Set<String> = emptySet()) : PreviewRepository by FakePreviewRepository() {
        val built = mutableListOf<String>()
        val lanes = mutableListOf<PreviewLane>()
        override val foregroundBusy = MutableStateFlow(false)
        var now = 0L
        var buildMillis = 100L

        override suspend fun hasThumbnail(source: PreviewSource) = source.key in cached

        override suspend fun thumbnail(source: PreviewSource, sizePx: Int, lane: PreviewLane): PreviewResult {
            built += source.key
            lanes += lane
            now += buildMillis
            return PreviewResult.Unavailable("fake")
        }
    }

    private fun TestScope.warmup(repo: Repo, copying: MutableStateFlow<Boolean> = MutableStateFlow(false)) =
        PreviewWarmup(repo, backgroundScope, copying, clock = { repo.now })

    @Test
    fun `builds each photo once, in order, in the warm-up lane`() = runTest {
        val repo = Repo()
        val warmup = warmup(repo)
        warmup.start("Latest Import", listOf(source(1), source(2), source(1), source(3)))
        runCurrent()

        assertEquals(listOf("k1", "k2", "k3"), repo.built)
        assertTrue(repo.lanes.all { it == PreviewLane.WARMUP })
        val state = warmup.state.value
        assertEquals(3, state.total)
        assertEquals(3, state.done)
        assertFalse(state.isRunning)
    }

    @Test
    fun `what's on disk is skipped, so a restart carries on`() = runTest {
        val repo = Repo(cached = setOf("k1", "k2"))
        val warmup = warmup(repo)
        warmup.start("Location", (1..4).map(::source))
        runCurrent()

        assertEquals(listOf("k3", "k4"), repo.built)
        assertEquals(4, warmup.state.value.done)
        assertEquals(2, warmup.state.value.built)
    }

    @Test
    fun `waits while the screen is waiting for a picture`() = runTest {
        val repo = Repo()
        repo.foregroundBusy.value = true
        val warmup = warmup(repo)
        warmup.start("Location", listOf(source(1)))
        runCurrent()

        assertTrue(repo.built.isEmpty())
        assertTrue(warmup.state.value.isWaiting)
        repo.foregroundBusy.value = false
        runCurrent()
        assertEquals(listOf("k1"), repo.built)
    }

    @Test
    fun `pauses while an import is copying`() = runTest {
        val repo = Repo()
        val copying = MutableStateFlow(true)
        val warmup = warmup(repo, copying)
        warmup.start("Location", listOf(source(1), source(2)))
        runCurrent()

        assertTrue(repo.built.isEmpty())
        copying.value = false
        runCurrent()
        assertEquals(listOf("k1", "k2"), repo.built)
    }

    @Test
    fun `pause, resume and cancel`() = runTest {
        val repo = Repo()
        val warmup = warmup(repo)
        warmup.pause()
        warmup.start("Location", (1..3).map(::source))
        warmup.pause()
        runCurrent()
        assertTrue(repo.built.isEmpty())
        assertTrue(warmup.state.value.isPaused)
        assertEquals("Building previews · Location · 0 of 3 · paused", warmupText(warmup.state.value))

        warmup.resume()
        runCurrent()
        assertEquals(3, repo.built.size)

        warmup.start("Again", listOf(source(9)))
        warmup.cancel()
        runCurrent()
        assertEquals(3, repo.built.size)
        assertFalse(warmup.state.value.isActive)
    }

    @Test
    fun `the estimate comes from the photos built so far`() = runTest {
        val repo = Repo()
        repo.buildMillis = 30_000
        val warmup = warmup(repo)
        val seen = mutableListOf<Long?>()
        warmup.start("Location", (1..5).map(::source))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { warmup.state.collect { seen += it.etaMillis } }
        runCurrent()

        // After the first of five: four left at 30 s each.
        assertTrue(seen.contains(120_000L))
        assertEquals("Building previews · Location · 1 of 5 · about 2 min", warmupText(WarmupState("Location", 5, 1, 1, true, etaMillis = 120_000)))
    }
}
