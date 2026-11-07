package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportReportTest {
    private val job = ImportJob(createdAt = 0, sourceTreeUri = "s", sourceLabel = "Card", locationId = 1, template = "t")

    private fun item(
        name: String,
        state: ImportItemState,
        dir: String = "2026",
        error: String? = null,
        sourceDeleted: Boolean = false,
    ) = ImportJobItem(
        jobId = 1,
        groupKey = name,
        sourceUri = "u",
        sourceName = name,
        sizeBytes = 1,
        targetRelativeDir = dir,
        targetName = name,
        state = state,
        error = error,
        sourceDeleted = sourceDeleted,
    )

    @Test
    fun `the current file is the one in flight`() {
        val report = ImportReport.of(job, listOf(item("A", ImportItemState.DONE), item("B", ImportItemState.VERIFYING)))
        assertEquals("B", report.currentFile)
    }

    @Test
    fun `folders are listed once, and only once something has landed in them`() {
        val report = ImportReport.of(
            job,
            listOf(
                item("A", ImportItemState.DONE, dir = "2026/a"),
                item("B", ImportItemState.DONE, dir = "2026/a"),
                item("C", ImportItemState.QUEUED, dir = "2026/b"),
                item("D", ImportItemState.DONE, dir = ""),
            ),
        )
        assertEquals(listOf("2026/a"), report.createdFolders)
    }

    @Test
    fun `failed files come with their reason`() {
        val report = ImportReport.of(job, listOf(item("A", ImportItemState.FAILED, error = "Not enough space")))
        assertEquals(listOf(FailedFile("A", "Not enough space")), report.failed)
    }

    @Test
    fun `delete results are counted and failures listed`() {
        val report = ImportReport.of(
            job,
            listOf(
                item("A", ImportItemState.DONE, sourceDeleted = true),
                item("B", ImportItemState.DONE, error = "Couldn't delete it from the card"),
            ),
        )
        assertEquals(1, report.deletedFromCard)
        assertEquals(listOf("B"), report.deleteFailures.map { it.name })
    }

    @Test
    fun `elapsed time and speed need a finished job`() {
        assertNull(ImportReport.of(job.copy(startedAt = 1000), emptyList()).elapsedMillis)

        val report = ImportReport.of(job.copy(startedAt = 1000, finishedAt = 3000, doneBytes = 4000), emptyList())
        assertEquals(2000L, report.elapsedMillis)
        assertEquals(2000L, report.averageBytesPerSecond)
    }

    @Test
    fun `time stopped counts towards neither elapsed time nor speed`() {
        val report = ImportReport.of(
            job.copy(startedAt = 1000, finishedAt = 41_000, doneBytes = 4000, activeMillis = 2000),
            emptyList(),
        )
        assertEquals(2000L, report.elapsedMillis)
        assertEquals(2000L, report.averageBytesPerSecond)
    }

    @Test
    fun `speed needs two samples`() {
        val meter = SpeedMeter()
        assertNull(meter.bytesPerSecond)
        meter.add(1000, 0)
        assertNull(meter.bytesPerSecond)
        meter.add(2000, 500)
        assertEquals(500L, meter.bytesPerSecond)
    }

    @Test
    fun `old samples drop out of the window`() {
        val meter = SpeedMeter(windowMillis = 1000)
        meter.add(0, 0)
        meter.add(1000, 10_000)
        meter.add(3000, 10_100)
        meter.add(4000, 10_200)
        assertEquals(100L, meter.bytesPerSecond)
    }

    @Test
    fun `the eta needs a speed`() {
        assertEquals(10L, etaSeconds(1000, 100))
        assertNull(etaSeconds(1000, null))
        assertNull(etaSeconds(1000, 0))
        assertEquals(0L, etaSeconds(-5, 100))
    }
}
