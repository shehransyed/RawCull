package dev.shehran.rawcull.data.importer

import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.local.ImportedFileDao
import dev.shehran.rawcull.data.local.LocationDao
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.ImportedFile
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.importer.ConflictNamer
import dev.shehran.rawcull.importer.CopyResult
import dev.shehran.rawcull.importer.DocumentInfo
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.importer.Sha256HeadHash
import dev.shehran.rawcull.importer.SourceReadException
import dev.shehran.rawcull.importer.StreamingHasher
import dev.shehran.rawcull.importer.TargetWriteException
import dev.shehran.rawcull.importer.sha256Of
import dev.shehran.rawcull.util.xmp.ParsedCullState
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Reported about four times a second while a job runs. */
fun interface ImportProgressListener {
    suspend fun onProgress(job: ImportJob, currentFile: String?, bytesPerSecond: Long)
}

/**
 * Copies a job's files into its Location. All of the import rules live here, on [DocumentIo] and
 * Room alone, so [ImportWorker] stays a thin shell and the rules can be tested without Android.
 *
 * Everything that must survive process death is in Room: an item is marked COPYING before its
 * target exists and its URI is stored before the first byte is written, so a restart can always
 * tell what it left behind.
 */
class ImportEngine(
    private val jobs: ImportJobDao,
    private val importedFiles: ImportedFileDao,
    private val locations: LocationDao,
    private val photos: RawPhotoDao,
    private val io: DocumentIo,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    private val runLock = Mutex()

    /** Runs [jobId] until it finishes or has to stop, and returns where it ended up. */
    suspend fun run(jobId: Long, listener: ImportProgressListener = ImportProgressListener { _, _, _ -> }): ImportJobStatus =
        runLock.withLock {
            withContext(ioDispatcher) {
                val job = jobs.getJob(jobId) ?: return@withContext ImportJobStatus.CANCELLED
                if (job.status == ImportJobStatus.CANCELLED) return@withContext job.status
                val location = locations.getById(job.locationId)
                if (location == null) {
                    jobs.finish(jobId, ImportJobStatus.PAUSED, clock())
                    return@withContext ImportJobStatus.PAUSED
                }
                JobRun(job, location, listener).execute()
            }
        }

    /** Clears up a job that won't run again; waits for a running one to stop first. */
    suspend fun discardPartials(jobId: Long) = runLock.withLock {
        withContext(ioDispatcher + NonCancellable) {
            jobs.getItems(jobId).filter { it.isInFlight }.forEach { item ->
                item.targetUri?.let(io::delete)
                jobs.updateItem(item.copy(state = ImportItemState.QUEUED, targetUri = null))
            }
        }
    }

    private val ImportJobItem.isInFlight: Boolean
        get() = state == ImportItemState.COPYING || state == ImportItemState.VERIFYING

    private sealed interface ItemResult {
        data object Finished : ItemResult
        data class Stop(val status: ImportJobStatus) : ItemResult
    }

    private class OutOfSpace : Exception()

    private inner class JobRun(
        private val initial: ImportJob,
        private val location: LibraryLocation,
        private val listener: ImportProgressListener,
    ) {
        private val jobId = initial.id
        private val dirUris = HashMap<String, String>()
        private val children = HashMap<String, MutableMap<String, DocumentInfo>>()
        private val recovered = HashSet<Long>()

        private val doneFiles = AtomicInteger()
        private val doneBytes = AtomicLong()
        private val skippedFiles = AtomicInteger()
        private val failedFiles = AtomicInteger()
        private val inFlightBytes = AtomicLong()

        @Volatile
        private var currentFile: String? = null

        private var copyNanos = 0L
        private var copiedBytes = 0L
        private var verifyNanos = 0L
        private var verifiedBytes = 0L
        private var firstFileMillis: Long? = null
        private val startedAt = clock()

        suspend fun execute(): ImportJobStatus {
            jobs.markRunning(jobId, initial.startedAt ?: startedAt)
            recoverInterrupted()
            recount()

            return try {
                val status = coroutineScope {
                    val ticker = launch { reportProgress() }
                    val outcome = try {
                        copyAll()
                    } finally {
                        ticker.cancel()
                    }
                    outcome
                }
                finish(status)
                status
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    flushCounters()
                    // Cancelling writes its status first; anything else is the system stopping us.
                    val current = jobs.getJob(jobId)?.status
                    val status = if (current == ImportJobStatus.CANCELLED) current else ImportJobStatus.PAUSED
                    jobs.finish(jobId, status, clock())
                }
                throw e
            }
        }

        /** An item left mid-copy restarts from nothing; whatever it wrote can't be trusted. */
        private suspend fun recoverInterrupted() {
            jobs.getItems(jobId).filter { it.isInFlight }.forEach { item ->
                item.targetUri?.let(io::delete)
                recovered += item.id
                jobs.updateItem(item.copy(state = ImportItemState.QUEUED, targetUri = null))
            }
        }

        private suspend fun recount() {
            val items = jobs.getItems(jobId)
            val done = items.filter { it.state == ImportItemState.DONE }
            doneFiles.set(done.size)
            doneBytes.set(done.sumOf { it.sizeBytes })
            skippedFiles.set(items.count { it.state == ImportItemState.SKIPPED_DUPLICATE && !it.role.isSidecar })
            failedFiles.set(items.count { it.state == ImportItemState.FAILED })
            flushCounters()
        }

        private suspend fun flushCounters() {
            jobs.updateProgress(
                jobId,
                doneFiles.get(),
                doneBytes.get() + inFlightBytes.get(),
                skippedFiles.get(),
                failedFiles.get(),
                activeMillis = initial.activeMillis + (clock() - startedAt),
            )
        }

        private suspend fun reportProgress() {
            var lastBytes = doneBytes.get()
            var lastTime = clock()
            while (coroutineContext.isActive) {
                delay(PROGRESS_INTERVAL_MS)
                flushCounters()
                val bytes = doneBytes.get() + inFlightBytes.get()
                val now = clock()
                val elapsed = (now - lastTime).coerceAtLeast(1L)
                val speed = ((bytes - lastBytes).coerceAtLeast(0L) * MILLIS_PER_SECOND) / elapsed
                lastBytes = bytes
                lastTime = now
                jobs.getJob(jobId)?.let { listener.onProgress(it, currentFile, speed) }
            }
        }

        private suspend fun copyAll(): ImportJobStatus {
            val groups = jobs.getItems(jobId).groupBy { it.groupKey }.values.map { it.toMutableList() }
            for (group in groups) {
                val hadWork = group.any { it.state == ImportItemState.QUEUED }
                for (index in group.indices) {
                    if (group[index].state != ImportItemState.QUEUED) continue
                    val result = try {
                        copyWithRetry(group, index)
                    } catch (e: OutOfSpace) {
                        failRemaining(NOT_ENOUGH_SPACE)
                        return ImportJobStatus.COMPLETED_WITH_ERRORS
                    }
                    if (result is ItemResult.Stop) return result.status
                }
                writeLibraryRow(group, hadWork)
            }
            return if (failedFiles.get() > 0) ImportJobStatus.COMPLETED_WITH_ERRORS else ImportJobStatus.COMPLETED
        }

        private suspend fun failRemaining(reason: String) {
            jobs.getItems(jobId).filter { it.state == ImportItemState.QUEUED }.forEach { item ->
                jobs.updateItem(item.copy(state = ImportItemState.FAILED, error = reason))
                failedFiles.incrementAndGet()
            }
        }

        /** D4: a copy that doesn't verify gets one more try, then fails alone and the job goes on. */
        private suspend fun copyWithRetry(group: MutableList<ImportJobItem>, index: Int): ItemResult {
            currentFile = group[index].sourceName
            var failure = "Copy failed"

            repeat(MAX_ATTEMPTS) {
                try {
                    val outcome = copyOnce(group, index)
                    if (outcome == null) return ItemResult.Finished
                    failure = outcome
                } catch (e: SourceReadException) {
                    discard(group, index)
                    if (!io.isAvailable(initial.sourceTreeUri)) return ItemResult.Stop(ImportJobStatus.SOURCE_LOST)
                    failure = "Couldn't read the file: ${e.message}"
                } catch (e: TargetWriteException) {
                    discard(group, index)
                    if (e.isOutOfSpace()) throw OutOfSpace()
                    if (!io.isAvailable(location.treeUri)) return ItemResult.Stop(ImportJobStatus.PAUSED)
                    failure = "Couldn't write the file: ${e.message}"
                } catch (e: IOException) {
                    discard(group, index)
                    if (e.isOutOfSpace()) throw OutOfSpace()
                    if (!io.isAvailable(location.treeUri)) return ItemResult.Stop(ImportJobStatus.PAUSED)
                    failure = "Couldn't create the file: ${e.message}"
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { discard(group, index) }
                    throw e
                }
            }

            val failed = group[index].copy(state = ImportItemState.FAILED, error = failure, targetUri = null)
            save(group, index, failed)
            failedFiles.incrementAndGet()
            log("failed ${failed.sourceName}: $failure")
            return ItemResult.Finished
        }

        private fun IOException.isOutOfSpace(): Boolean {
            val text = message.orEmpty() + cause?.message.orEmpty()
            return text.contains("ENOSPC") || text.contains("No space left")
        }

        /** The partial copy goes, so nothing unverified is ever left behind in the library. */
        private suspend fun discard(group: MutableList<ImportJobItem>, index: Int) {
            inFlightBytes.set(0L)
            val item = group[index]
            item.targetUri?.let { uri ->
                io.delete(uri)
                children[dirUris[item.targetRelativeDir]]?.remove(item.targetName.key())
            }
            save(group, index, item.copy(state = ImportItemState.QUEUED, targetUri = null))
        }

        /** Returns null once the item is settled, or why this attempt failed verification. */
        private suspend fun copyOnce(group: MutableList<ImportJobItem>, index: Int): String? {
            save(group, index, group[index].copy(state = ImportItemState.COPYING, targetUri = null, error = null))
            val dirUri = ensureDirectory(group[index].targetRelativeDir)

            if (keepExistingSidecar(group, index, dirUri)) return null

            val targetUri = createTarget(group, index, dirUri)
            save(group, index, group[index].copy(targetUri = targetUri))

            val copied = copy(group[index])
            save(
                group,
                index,
                group[index].copy(state = ImportItemState.VERIFYING, headHash = copied.headHash, fullHash = copied.fullHash),
            )

            val mismatch = verify(group[index], copied)
            if (mismatch != null) {
                discard(group, index)
                return mismatch
            }

            complete(group, index, copied)
            return null
        }

        /** The sidecar in the library is the source of truth, so one that appeared since planning stays. */
        private suspend fun keepExistingSidecar(group: MutableList<ImportJobItem>, index: Int, dirUri: String): Boolean {
            val item = group[index]
            if (!item.role.isSidecar) return false
            val existing = childrenOf(dirUri)[item.targetName.key()] ?: return false
            save(group, index, item.copy(state = ImportItemState.SKIPPED_DUPLICATE, targetUri = existing.uri))
            return true
        }

        private suspend fun copy(item: ImportJobItem): CopyResult {
            val coroutineJob = coroutineContext[Job]
            val target = checkNotNull(item.targetUri)
            val started = System.nanoTime()

            val input = try {
                io.openInput(item.sourceUri)
            } catch (e: IOException) {
                throw SourceReadException(e)
            }
            val result = input.use { source ->
                val output = try {
                    io.openOutput(target)
                } catch (e: IOException) {
                    throw TargetWriteException(e)
                }
                try {
                    output.use { sink ->
                        StreamingHasher.copy(source, sink) { bytes ->
                            coroutineJob?.ensureActive()
                            inFlightBytes.addAndGet(bytes.toLong())
                        }
                    }
                } catch (e: SourceReadException) {
                    throw e
                } catch (e: TargetWriteException) {
                    throw e
                } catch (e: IOException) {
                    throw TargetWriteException(e) // Closing is what pushes the last bytes out.
                }
            }

            copyNanos += System.nanoTime() - started
            copiedBytes += result.bytes
            if (firstFileMillis == null) firstFileMillis = clock() - startedAt
            return result
        }

        /** Null if the copy is good, otherwise the reason it isn't. */
        private fun verify(item: ImportJobItem, copied: CopyResult): String? {
            if (copied.bytes != item.sizeBytes) return "Copied ${copied.bytes} of ${item.sizeBytes} bytes"
            val target = checkNotNull(item.targetUri)
            val started = System.nanoTime()

            val matches = try {
                when (initial.verifyMode) {
                    VerifyMode.FULL -> io.openInput(target).use { sha256Of(it) } == copied.fullHash
                    VerifyMode.QUICK -> {
                        val size = io.info(target)?.sizeBytes
                        size == copied.bytes && io.openInput(target).use(Sha256HeadHash::of) == copied.headHash
                    }
                }
            } catch (e: IOException) {
                return "Couldn't read the copy back: ${e.message}"
            }

            verifyNanos += System.nanoTime() - started
            verifiedBytes += if (initial.verifyMode == VerifyMode.FULL) copied.bytes else minOf(copied.bytes, HEAD_SAMPLE)
            return if (matches) null else "The copy didn't match the source"
        }

        private suspend fun complete(group: MutableList<ImportJobItem>, index: Int, copied: CopyResult) {
            val item = group[index]
            // Indexed first: a crash in between repeats the copy, which is safe, and never leaves a
            // file marked done that the duplicate check doesn't know about.
            if (!item.role.isSidecar) {
                importedFiles.insert(
                    ImportedFile(
                        headHash = copied.headHash,
                        sizeBytes = copied.bytes,
                        originalName = item.sourceName,
                        targetUri = checkNotNull(item.targetUri),
                        importedAt = clock(),
                        jobId = jobId,
                    ),
                )
            }
            save(group, index, item.copy(state = ImportItemState.DONE))
            inFlightBytes.set(0L)
            doneFiles.incrementAndGet()
            doneBytes.addAndGet(item.sizeBytes)
        }

        private suspend fun save(group: MutableList<ImportJobItem>, index: Int, item: ImportJobItem) {
            jobs.updateItem(item)
            group[index] = item
        }

        private fun String.key() = lowercase(Locale.ROOT)

        private fun childrenOf(dirUri: String): MutableMap<String, DocumentInfo> = children.getOrPut(dirUri) {
            io.listChildren(dirUri).associateByTo(HashMap()) { it.name.key() }
        }

        /** One children query per directory, then cached for the job. */
        private fun ensureDirectory(relativeDir: String): String {
            dirUris[relativeDir]?.let { return it }
            var parent = io.rootUri(location.treeUri)
            var path = ""
            if (relativeDir.isNotEmpty()) {
                for (segment in relativeDir.split('/')) {
                    path = if (path.isEmpty()) segment else "$path/$segment"
                    parent = dirUris[path] ?: findOrCreateDirectory(parent, segment).also { dirUris[path] = it }
                }
            }
            dirUris[relativeDir] = parent
            return parent
        }

        private fun findOrCreateDirectory(parentUri: String, name: String): String {
            val siblings = childrenOf(parentUri)
            siblings[name.key()]?.let { existing ->
                if (existing.isDirectory) return existing.uri
                throw IOException("A file is in the way of the folder $name")
            }
            val created = io.createDirectory(parentUri, name)
            if (created.displayName != name) {
                io.delete(created.uri)
                throw IOException("The provider wouldn't name the folder $name")
            }
            siblings[name.key()] = DocumentInfo(created.uri, name, isDirectory = true, sizeBytes = 0L, lastModified = null)
            children[created.uri] = HashMap()
            return created.uri
        }

        /** Creates the target under its planned name, or the group's next free suffix (D5). */
        private suspend fun createTarget(group: MutableList<ImportJobItem>, index: Int, dirUri: String): String {
            repeat(MAX_NAME_ATTEMPTS) {
                val item = group[index]
                val name = item.targetName
                val siblings = childrenOf(dirUri)
                val existing = siblings[name.key()]

                when {
                    existing == null -> {
                        val uri = createNamed(dirUri, item, name)
                        if (uri != null) {
                            siblings[name.key()] = DocumentInfo(uri, name, false, 0L, null)
                            return uri
                        }
                        nextSuffix(group, dirUri)
                    }
                    // Killed between creating this and storing its URI: an empty file we made.
                    item.id in recovered && existing.sizeBytes == 0L -> {
                        io.delete(existing.uri)
                        siblings.remove(name.key())
                        recovered -= item.id
                    }
                    else -> nextSuffix(group, dirUri)
                }
            }
            throw IOException("No free name for ${group[index].sourceName}")
        }

        /** Null if the provider insisted on another name, which counts as a conflict. */
        private fun createNamed(dirUri: String, item: ImportJobItem, name: String): String? {
            val created = io.createFile(dirUri, mimeTypeOf(item), name)
            if (created.displayName == name) return created.uri

            // An appended extension renames cleanly; a de-duplicated name fails, as the name is taken.
            val renamed = io.rename(created.uri, name)
            if (renamed != null && io.info(renamed)?.name == name) return renamed
            io.delete(renamed ?: created.uri)
            return null
        }

        /** Moves the whole group to the next suffix, so a RAW and its companion keep sharing a stem. */
        private suspend fun nextSuffix(group: MutableList<ImportJobItem>, dirUri: String) {
            val stem = group.first().sourceName.substringBeforeLast('.')
            val siblings = childrenOf(dirUri)
            val owned = group.map { it.targetName.key() }.toSet()
            var attempt = 1
            while (true) {
                val names = group.map { ConflictNamer.suffixed(it.sourceName, stem, attempt) }
                val free = names.none { name -> name.key() !in owned && name.key() in siblings || name.key() in owned }
                if (free) {
                    group.forEachIndexed { index, item -> renameItem(group, index, item, names[index], dirUri) }
                    return
                }
                attempt++
            }
        }

        private suspend fun renameItem(
            group: MutableList<ImportJobItem>,
            index: Int,
            item: ImportJobItem,
            name: String,
            dirUri: String,
        ) {
            val targetUri = item.targetUri
            if (item.state == ImportItemState.DONE && targetUri != null) {
                val renamed = io.rename(targetUri, name) ?: return
                val siblings = childrenOf(dirUri)
                siblings.remove(item.targetName.key())?.let { siblings[name.key()] = it.copy(uri = renamed, name = name) }
                save(group, index, item.copy(targetName = name, targetUri = renamed))
            } else if (item.state != ImportItemState.SKIPPED_DUPLICATE) {
                save(group, index, item.copy(targetName = name))
            }
        }

        /** `.HIF` isn't an extension providers map to image/heif, so they would append one. */
        private fun mimeTypeOf(item: ImportJobItem): String {
            if (item.role.isSidecar) return OCTET_STREAM
            val format = PhotoFormat.fromFileName(item.sourceName)
            return if (format == PhotoFormat.HEIF) OCTET_STREAM else format.mimeType
        }

        /**
         * Inserted as each group lands, so the Library fills up while the import runs.
         *
         * @param hadWork false for a group an earlier run finished. Its row only needs writing if
         * that run was killed in between, and anything else would overwrite it for nothing.
         */
        private suspend fun writeLibraryRow(group: List<ImportJobItem>, hadWork: Boolean) {
            val primary = group.firstOrNull { it.role == ImportItemRole.PRIMARY && it.state == ImportItemState.DONE } ?: return
            val primaryUri = primary.targetUri ?: return
            val stem = primary.targetName.substringBeforeLast('.')
            val id = AssetIds.of(location.treeUri, primary.targetRelativeDir, stem, primary.assetDiscriminator)
            val existing = photos.getPhotoById(id)
            if (!hadWork && existing != null) return

            val companion = group.landed(ImportItemRole.COMPANION)
            val adobe = group.landed(ImportItemRole.ADOBE_SIDECAR)
            val darktable = group.landed(ImportItemRole.DARKTABLE_SIDECAR)
            val sidecar = adobe ?: darktable
            val directoryUri = try {
                ensureDirectory(primary.targetRelativeDir)
            } catch (e: IOException) {
                log("no row for ${primary.targetName}: $e") // The next scan picks the file up.
                return
            }

            val info = io.info(primaryUri)
            val metadata = ImportMetadataCodec.decode(primary.metadataJson)
            val sidecarState = sidecar?.targetUri?.let(::readSidecar)
            val sidecarTime = sidecar?.targetUri?.let { io.info(it)?.lastModified }
            val format = PhotoFormat.fromFileName(primary.targetName)
            val lastModified = info?.lastModified ?: 0L
            val metadataLoaded = metadata != null && (sidecar == null || sidecarState != null)

            val fileFields: PhotoAsset.() -> PhotoAsset = {
                copy(
                    relativeDir = primary.targetRelativeDir,
                    parentDocUri = directoryUri,
                    fileName = primary.targetName,
                    fileUri = primaryUri,
                    format = format,
                    fileSizeBytes = primary.sizeBytes,
                    fileLastModified = lastModified,
                    companionFileName = companion?.targetName,
                    companionFileUri = companion?.targetUri,
                    companionFileSizeBytes = companion?.sizeBytes,
                    companionFormat = companion?.let { PhotoFormat.fromFileName(it.targetName) },
                    xmpFileUri = sidecar?.targetUri,
                    darktableXmpUri = darktable?.targetUri,
                    pairMode = when {
                        companion != null -> PairMode.RAW_AND_RASTER
                        format.isRaw -> PairMode.RAW_ONLY
                        else -> PairMode.RASTER_ONLY
                    },
                    lastImportJobId = jobId,
                )
            }

            val row = existing?.fileFields() ?: PhotoAsset(
                id = id,
                rootUri = location.treeUri,
                fileName = primary.targetName,
                fileUri = primaryUri,
                format = format,
                fileSizeBytes = primary.sizeBytes,
                dateTaken = metadata?.dateTaken ?: primary.captureDate.takeIf { it > 0 } ?: lastModified,
                metadataLoaded = metadataLoaded,
                cullStatus = sidecarState?.cullStatus ?: CullStatus.UNFLAGGED,
                rating = sidecarState?.rating ?: 0,
                colorLabel = sidecarState?.colorLabel ?: ColorLabel.NONE,
                tags = sidecarState?.tags.orEmpty(),
                width = metadata?.width ?: 0,
                height = metadata?.height ?: 0,
                cameraMake = metadata?.cameraMake,
                cameraModel = metadata?.cameraModel,
                lensModel = metadata?.lensModel,
                shutterSpeed = metadata?.shutterSpeed,
                aperture = metadata?.aperture,
                iso = metadata?.iso,
                focalLength = metadata?.focalLength,
                exposureBias = metadata?.exposureBias,
                orientation = metadata?.orientation ?: 0,
                lastModified = clock(),
                xmpLastSyncedMtime = sidecarTime.takeIf { sidecarState != null },
            ).fileFields()
            photos.upsertAll(listOf(row))
        }

        private fun List<ImportJobItem>.landed(role: ImportItemRole): ImportJobItem? = firstOrNull {
            it.role == role && it.targetUri != null &&
                (it.state == ImportItemState.DONE || it.state == ImportItemState.SKIPPED_DUPLICATE)
        }

        /** Null if it can't be read or parsed: the next scan then reports it the usual way. */
        private fun readSidecar(uri: String): ParsedCullState? = try {
            val bytes = io.openInput(uri).use { it.readBytes() }
            XmpDocument.parse(bytes).getOrNull()?.let(XmpMapper::read)
        } catch (e: IOException) {
            null
        }

        private suspend fun finish(status: ImportJobStatus) {
            val finished = status == ImportJobStatus.COMPLETED || status == ImportJobStatus.COMPLETED_WITH_ERRORS
            if (finished && initial.deleteSourceAfter) deleteVerifiedSources()
            flushCounters()
            jobs.finish(jobId, status, clock())
            logTotals(status)
        }

        /** D10: a group only goes once all of it is safely in the library; a failed delete isn't fatal. */
        private suspend fun deleteVerifiedSources() {
            val groups = jobs.getItems(jobId).groupBy { it.groupKey }.values
            for (group in groups) {
                val copied = group.filterNot { it.state == ImportItemState.SKIPPED_DUPLICATE }
                if (copied.isEmpty() || copied.any { it.state != ImportItemState.DONE }) continue
                for (item in copied) {
                    if (item.sourceDeleted) continue
                    val deleted = io.delete(item.sourceUri)
                    jobs.updateItem(
                        if (deleted) item.copy(sourceDeleted = true) else item.copy(error = "Couldn't delete it from the card"),
                    )
                }
            }
        }

        private fun logTotals(status: ImportJobStatus) {
            log(
                "job=$jobId status=$status files=${doneFiles.get()} skipped=${skippedFiles.get()} " +
                    "failed=${failedFiles.get()} bytes=$copiedBytes copyMBs=${rate(copiedBytes, copyNanos)} " +
                    "verifyMBs=${rate(verifiedBytes, verifyNanos)} verify=${initial.verifyMode} " +
                    "firstFileMs=${firstFileMillis ?: -1} totalMs=${clock() - startedAt}",
            )
        }

        private fun rate(bytes: Long, nanos: Long): String {
            if (nanos <= 0) return "0"
            return "%.1f".format(Locale.ROOT, bytes / MEGABYTE / (nanos / NANOS_PER_SECOND))
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_NAME_ATTEMPTS = 50
        const val PROGRESS_INTERVAL_MS = 250L // At most four database writes a second
        const val MILLIS_PER_SECOND = 1000L
        const val OCTET_STREAM = "application/octet-stream"
        const val NOT_ENOUGH_SPACE = "Not enough space"
        const val HEAD_SAMPLE = 64L * 1024L
        const val MEGABYTE = 1024.0 * 1024.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
