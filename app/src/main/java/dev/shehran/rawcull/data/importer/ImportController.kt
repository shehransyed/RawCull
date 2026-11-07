package dev.shehran.rawcull.data.importer

import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.importer.CandidateFile
import dev.shehran.rawcull.importer.DocumentInfo
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.importer.ExistingTarget
import dev.shehran.rawcull.importer.ExistingTargets
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.importer.ImportPlanner
import dev.shehran.rawcull.importer.ImportSelection
import dev.shehran.rawcull.importer.ImportSource
import dev.shehran.rawcull.importer.PlannedItem
import dev.shehran.rawcull.importer.Sha256HeadHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/** Hands a job to the background. A seam, so job handling can be tested without WorkManager. */
interface ImportScheduler {
    /** Unique: only ever one import at a time. */
    fun enqueue(jobId: Long)
    fun cancel()
}

data class ImportRequest(
    val source: ImportSource,
    val location: LibraryLocation,
    val candidates: List<ImportCandidate>,
    val selection: ImportSelection,
    val template: (Long?) -> String,
    val verifyMode: VerifyMode,
    val deleteFromCard: Boolean,
)

sealed interface ImportStart {
    data class Started(val jobId: Long) : ImportStart
    data class Refused(val message: String) : ImportStart
}

/** Starts, resumes and cancels jobs. The copying itself is [ImportEngine]'s. */
class ImportController(
    private val jobs: ImportJobDao,
    private val engine: ImportEngine,
    private val io: DocumentIo,
    private val scheduler: ImportScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun start(request: ImportRequest): ImportStart = withContext(ioDispatcher) {
        if (jobs.getActiveJob() != null) {
            return@withContext ImportStart.Refused("An import is already running. Wait for it to finish first.")
        }

        val rootUri = io.rootUri(request.location.treeUri)
        val planned = ImportPlanner.plan(
            candidates = request.candidates,
            selection = request.selection,
            template = request.template,
            existingTargets = LibraryTargets(io, rootUri),
            sourceHead = ::headOf,
        )
        if (planned.isEmpty()) return@withContext ImportStart.Refused("Nothing is selected.")

        val copied = planned.filterNot { it.skippedDuplicate }
        val job = ImportJob(
            createdAt = clock(),
            sourceTreeUri = request.source.treeUri,
            sourceLabel = request.source.label,
            locationId = request.location.id,
            template = planned.first().targetRelativeDir,
            verifyMode = request.verifyMode,
            // Only a detected card is ever emptied. A folder picked by hand may be someone's only copy.
            deleteSourceAfter = request.deleteFromCard && request.source.volumeUuid != null,
            totalFiles = copied.size,
            totalBytes = copied.sumOf { it.sizeBytes },
            skippedFiles = planned.count { it.skippedDuplicate && !it.role.isSidecar },
        )
        val jobId = jobs.insertJobWithItems(job, planned.map(::toItem))
        scheduler.enqueue(jobId)
        ImportStart.Started(jobId)
    }

    private fun headOf(file: CandidateFile): String? = try {
        io.openInput(file.uri).use(Sha256HeadHash::of)
    } catch (e: IOException) {
        null
    }

    private fun toItem(item: PlannedItem) = ImportJobItem(
        jobId = 0,
        groupKey = item.groupKey,
        sourceUri = item.sourceUri,
        sourceName = item.sourceName,
        sizeBytes = item.sizeBytes,
        targetRelativeDir = item.targetRelativeDir,
        targetName = item.targetName,
        targetUri = item.existingTargetUri,
        state = if (item.skippedDuplicate) ImportItemState.SKIPPED_DUPLICATE else ImportItemState.QUEUED,
        role = item.role,
        captureDate = item.captureDate,
        metadataJson = item.metadataJson,
        assetDiscriminator = item.assetDiscriminator,
    )

    /** For a job that stopped: the card went away, or the system ended it. */
    suspend fun resume(jobId: Long): ImportStart = withContext(ioDispatcher) {
        val active = jobs.getActiveJob()
        if (active != null && active.id != jobId) {
            return@withContext ImportStart.Refused("Another import is running. Wait for it to finish first.")
        }
        val job = jobs.getJob(jobId) ?: return@withContext ImportStart.Refused("That import no longer exists.")
        if (!io.isAvailable(job.sourceTreeUri)) {
            return@withContext ImportStart.Refused("The card isn't connected yet.")
        }
        jobs.setStatus(jobId, ImportJobStatus.QUEUED)
        scheduler.enqueue(jobId)
        ImportStart.Started(jobId)
    }

    suspend fun retryFailed(jobId: Long): ImportStart = withContext(ioDispatcher) {
        jobs.requeueFailed(jobId)
        resume(jobId)
    }

    /** Finished files stay; only the one in progress is removed. */
    suspend fun cancel(jobId: Long) = withContext(ioDispatcher) {
        // First, so the worker knows this was the user and not the system.
        jobs.setStatus(jobId, ImportJobStatus.CANCELLED)
        scheduler.cancel()
        engine.discardPartials(jobId)
    }

    /** WorkManager restarts what it knows about; this covers a job it lost track of. */
    suspend fun ensureScheduled() {
        jobs.getActiveJob()?.let { scheduler.enqueue(it.id) }
    }
}

/** What's in the library's target folders, read once per folder. */
private class LibraryTargets(private val io: DocumentIo, private val rootUri: String) : ExistingTargets {
    private val folders = HashMap<String, Map<String, DocumentInfo>?>()

    override fun find(dir: String, name: String): ExistingTarget? {
        val found = listing(dir)?.get(name.lowercase(Locale.ROOT)) ?: return null
        if (found.isDirectory) return ExistingTarget(found.uri, -1L) { null }
        return ExistingTarget(found.uri, found.sizeBytes) { headOf(found.uri) }
    }

    private fun headOf(uri: String): String? = try {
        io.openInput(uri).use(Sha256HeadHash::of)
    } catch (e: IOException) {
        null
    }

    private fun listing(dir: String): Map<String, DocumentInfo>? = folders.getOrPut(dir) {
        if (dir.isEmpty()) return@getOrPut list(rootUri)
        val parent = listing(dir.substringBeforeLast('/', "")) ?: return@getOrPut null
        val folder = parent[dir.substringAfterLast('/').lowercase(Locale.ROOT)]?.takeIf { it.isDirectory }
        folder?.let { list(it.uri) }
    }

    private fun list(uri: String): Map<String, DocumentInfo>? = try {
        io.listChildren(uri).associateBy { it.name.lowercase(Locale.ROOT) }
    } catch (e: IOException) {
        null
    }
}
