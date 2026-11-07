package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem

data class FailedFile(val name: String, val reason: String)

/** What the progress and summary screen says about a job, all derived from Room. */
data class ImportReport(
    val currentFile: String?,
    val createdFolders: List<String>,
    val failed: List<FailedFile>,
    val deletedFromCard: Int,
    val deleteFailures: List<FailedFile>,
    val elapsedMillis: Long?,
    val averageBytesPerSecond: Long?,
) {
    companion object {
        fun of(job: ImportJob, items: List<ImportJobItem>): ImportReport {
            // Jobs from before v6 have no active time, so wall-clock time is the best they can show.
            val wallClock = job.startedAt?.let { start -> job.finishedAt?.minus(start) }
            val elapsed = wallClock?.let { job.activeMillis.takeIf { active -> active > 0 } ?: it }
            return ImportReport(
                currentFile = items.firstOrNull {
                    it.state == ImportItemState.COPYING || it.state == ImportItemState.VERIFYING
                }?.sourceName,
                createdFolders = items.filter { it.state == ImportItemState.DONE }
                    .map { it.targetRelativeDir }
                    .filter { it.isNotEmpty() }
                    .distinct(),
                failed = items.filter { it.state == ImportItemState.FAILED }
                    .map { FailedFile(it.sourceName, it.error ?: "Copy failed") },
                deletedFromCard = items.count { it.sourceDeleted },
                deleteFailures = items.filter { it.state == ImportItemState.DONE && !it.sourceDeleted && it.error != null }
                    .map { FailedFile(it.sourceName, checkNotNull(it.error)) },
                elapsedMillis = elapsed,
                averageBytesPerSecond = elapsed?.takeIf { it > 0 }?.let { job.doneBytes * MILLIS_PER_SECOND / it },
            )
        }

        private const val MILLIS_PER_SECOND = 1000L
    }
}

/** Speed over the last few seconds, which says more than the average since the job began. */
class SpeedMeter(private val windowMillis: Long = DEFAULT_WINDOW_MS) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(timeMillis: Long, totalBytes: Long) {
        samples.addLast(timeMillis to totalBytes)
        while (samples.size > 2 && samples.first().first < timeMillis - windowMillis) samples.removeFirst()
    }

    val bytesPerSecond: Long?
        get() {
            val (startTime, startBytes) = samples.firstOrNull() ?: return null
            val (endTime, endBytes) = samples.last()
            if (endTime <= startTime) return null
            return (endBytes - startBytes).coerceAtLeast(0L) * MILLIS_PER_SECOND / (endTime - startTime)
        }

    private companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** Seconds left at [bytesPerSecond], or null while there's no telling. */
fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long?): Long? =
    bytesPerSecond?.takeIf { it > 0 }?.let { remainingBytes.coerceAtLeast(0L) / it }
