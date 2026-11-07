package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.ui.settings.cacheLimitLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBenchmarkTest {
    @Test
    fun `percentiles are nearest rank`() {
        val values = (1..10).map { it.toDouble() }
        assertEquals(5.0, BenchmarkTable.percentile(values, 50), 0.0)
        assertEquals(9.0, BenchmarkTable.percentile(values, 90), 0.0)
        assertEquals(7.0, BenchmarkTable.percentile(listOf(7.0), 90), 0.0)
        assertEquals(0.0, BenchmarkTable.percentile(emptyList(), 50), 0.0)
    }

    @Test
    fun `the table has a row per stage and format, in stage order, with the D13 target`() {
        fun sample(stage: BenchmarkStage, format: String, total: Double) =
            BenchmarkSample(stage, format, PreviewTiming(HitLevel.FILE, openMs = 1.0, scanMs = 2.0, readMs = 3.0, decodeMs = 4.0, totalMs = total))
        val samples = listOf(
            sample(BenchmarkStage.COLD_FIT, "ARW", 200.0),
            sample(BenchmarkStage.COLD_THUMBNAIL, "CR3", 70.0),
            sample(BenchmarkStage.COLD_THUMBNAIL, "ARW", 50.0),
            sample(BenchmarkStage.COLD_THUMBNAIL, "ARW", 90.0),
        )
        val lines = BenchmarkTable.format(samples, "title").lines()

        assertEquals("title", lines[0])
        assertEquals(6, lines.size)
        assertEquals("| Cold thumbnail (file) | ARW | 2 | 50.0 | 90.0 | ≤ 80.0 | 1.0 | 2.0 | 3.0 | 4.0 |", lines[3])
        assertTrue(lines[4].startsWith("| Cold thumbnail (file) | CR3 | 1 |"))
        assertTrue(lines[5].startsWith("| Cold loupe fit | ARW |"))
    }

    @Test
    fun `cache limits read as people say them`() {
        assertEquals(listOf("256 MB", "512 MB", "1 GB", "2 GB", "4 GB"), listOf(256, 512, 1024, 2048, 4096).map(::cacheLimitLabel))
    }

    @Test
    fun `a run goes cold, then disk, then loupe, then tiles`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val repo = object : PreviewRepository by FakePreviewRepository() {
            override suspend fun forget(sources: List<PreviewSource>) {
                calls += "forget"
            }

            override fun clearMemory() {
                calls += "clearMemory"
            }

            override suspend fun thumbnail(source: PreviewSource, sizePx: Int, lane: PreviewLane): PreviewResult {
                calls += "thumbnail"
                return PreviewResult.Unavailable("fake")
            }

            override suspend fun fitPreview(source: PreviewSource, widthPx: Int, heightPx: Int, lane: PreviewLane): PreviewResult {
                calls += "fit"
                return PreviewResult.Unavailable("fake")
            }
        }
        val photo = PreviewBenchmark.Photo(PreviewSource("u", dev.shehran.rawcull.data.model.PhotoFormat.ARW, null, "k"), "ARW")
        var progress = 0 to 0
        PreviewBenchmark(repo).run(listOf(photo), 1000, 800) { done, total -> progress = done to total }

        assertEquals(listOf("forget", "thumbnail", "clearMemory", "thumbnail", "clearMemory", "fit", "fit"), calls) // Cold fit, then at once from RAM
        assertEquals(5 to 5, progress)
    }
}
