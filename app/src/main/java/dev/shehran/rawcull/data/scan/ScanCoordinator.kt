package dev.shehran.rawcull.data.scan

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.Converters
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.model.EnrichedMetadata
import dev.shehran.rawcull.data.model.EnrichedXmp
import dev.shehran.rawcull.data.model.EnrichmentResult
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.util.RawMetadataParser
import dev.shehran.rawcull.util.SafPermissions
import dev.shehran.rawcull.util.XmpSidecarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

sealed interface ScanState {
    data object Idle : ScanState
    data class Listing(val rootUri: String, val dirsVisited: Int, val filesSeen: Int) : ScanState
    data class Enriching(
        val rootUri: String,
        val photoCount: Int,
        val folderCount: Int,
        val done: Int,
        val total: Int,
    ) : ScanState
    data class Complete(val rootUri: String, val photoCount: Int, val folderCount: Int) : ScanState
    data class SourceUnavailable(val rootUri: String) : ScanState
}

/**
 * Scans one source at a time. Phase A lists the tree and updates Room, so the grid appears at once;
 * phase B then fills in EXIF and sidecar data lazily, nearest the current selection first.
 */
class ScanCoordinator(
    context: Context,
    private val database: AppDatabase,
    private val dao: RawPhotoDao,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val walker = SafTreeWalker(appContext.contentResolver)
    private val converters = Converters()

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var queue: EnrichmentQueue? = null

    @Volatile
    private var focusId: String? = null

    /** Starts scanning [rootUri], cancelling any scan already running. */
    @Synchronized
    fun start(rootUri: String) {
        val previous = job
        previous?.cancel()
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            scan(rootUri)
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        queue = null
        _state.value = ScanState.Idle
    }

    /** Moves enrichment to [id] and its neighbours. */
    fun prioritize(id: String) {
        focusId = id
        queue?.prioritize(id)
    }

    private suspend fun scan(rootUri: String) {
        try {
            val listing = runPhaseA(rootUri)
            runPhaseB(rootUri, listing)
            _state.value = ScanState.Complete(rootUri, listing.photoCount, listing.folderCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            onSourceLost(rootUri, e)
        } catch (e: SecurityException) {
            onSourceLost(rootUri, e)
        } catch (e: IllegalArgumentException) {
            onSourceLost(rootUri, e)
        } finally {
            queue = null
        }
    }

    /** The catalog stays as it is: the rows are still browsable, and a retry reconciles them. */
    private fun onSourceLost(rootUri: String, cause: Exception) {
        Log.w(TAG, "Source unavailable: $rootUri", cause)
        _state.value = ScanState.SourceUnavailable(rootUri)
    }

    private data class Listing(
        val photoCount: Int,
        val folderCount: Int,
        val sidecarLastModified: Map<String, Long>,
    )

    private suspend fun runPhaseA(rootUri: String): Listing {
        val started = SystemClock.elapsedRealtime()
        val treeUri = Uri.parse(rootUri)
        _state.value = ScanState.Listing(rootUri, dirsVisited = 0, filesSeen = 0)

        val walk = try {
            walker.walk(treeUri) { dirs, files -> _state.value = ScanState.Listing(rootUri, dirs, files) }
        } catch (e: IllegalStateException) {
            // Providers can surface a dying volume this way; here it can't be a local bug in Room.
            throw IOException("Walk failed", e)
        }
        val grouping = AssetGrouper.group(walk.entries)
        // An empty listing is only believable if the volume is still there.
        if (grouping.assets.isEmpty() && !SafPermissions.isAvailable(appContext, treeUri)) {
            throw FileNotFoundException("Source went away during the listing")
        }

        val merge = database.withTransaction {
            val existing = dao.getByRoot(rootUri).associateBy { it.id }
            val result = ScanMerger.merge(rootUri, existing, grouping.assets)
            result.toUpsert.chunked(DB_CHUNK).forEach { dao.upsertAll(it) }
            result.toDelete.chunked(DB_CHUNK).forEach { dao.deleteByIds(it) }
            result
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        val filesPerSecond = if (elapsed > 0) walk.entries.size * 1000L / elapsed else walk.entries.size.toLong()
        Log.i(
            TAG,
            "phaseA durationMs=$elapsed files=${walk.entries.size} dirs=${walk.dirsVisited} " +
                "assets=${grouping.assets.size} orphanSidecars=${grouping.orphanSidecars.size} " +
                "upserted=${merge.toUpsert.size} deleted=${merge.toDelete.size} filesPerSec=$filesPerSecond",
        )
        return Listing(
            photoCount = grouping.assets.size,
            folderCount = grouping.assets.map { it.primary.relativeDir }.distinct().size,
            sidecarLastModified = merge.sidecarLastModified,
        )
    }

    private suspend fun runPhaseB(rootUri: String, listing: Listing) {
        val rows = dao.getByRoot(rootUri)
        val pending = rows.filterNot { it.metadataLoaded }
        if (pending.isEmpty()) return

        val started = SystemClock.elapsedRealtime()
        val rowsById = pending.associateBy { it.id }
        val workQueue = EnrichmentQueue(rows.map { it.id }, rowsById.keys)
        focusId?.let(workQueue::prioritize)
        queue = workQueue

        val total = pending.size
        val done = AtomicInteger(0)
        val perFileMillis = java.util.Collections.synchronizedList(ArrayList<Long>(total))
        val batcher = ResultBatcher()

        fun publish(count: Int) {
            _state.value = ScanState.Enriching(rootUri, listing.photoCount, listing.folderCount, count, total)
        }
        publish(0)

        try {
            coroutineScope {
                val flusher = launch {
                    while (isActive) {
                        delay(FLUSH_INTERVAL_MS)
                        batcher.flush()
                    }
                }
                val semaphore = Semaphore(MAX_CONCURRENT_READS)
                val dispatcher = launch {
                    while (true) {
                        semaphore.acquire()
                        val id = workQueue.next()
                        if (id == null) {
                            semaphore.release()
                            break
                        }
                        launch {
                            try {
                                val fileStarted = SystemClock.elapsedRealtime()
                                val row = rowsById.getValue(id)
                                enrich(rootUri, row, listing.sidecarLastModified[id])?.let { batcher.add(it) }
                                perFileMillis += SystemClock.elapsedRealtime() - fileStarted
                                publish(done.incrementAndGet())
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }
                dispatcher.join()
                flusher.cancel()
            }
            batcher.flush()
        } catch (e: IOException) {
            batcher.flush() // Keep what was read before the card went away.
            throw e
        }

        val median = perFileMillis.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        Log.i(
            TAG,
            "phaseB durationMs=${SystemClock.elapsedRealtime() - started} files=$total medianFileMs=$median",
        )
    }

    /**
     * Reads one asset. Returns null to skip it (the file vanished but the volume is fine); throws
     * if the volume is gone, so that fallback values never get stored as if they had been read.
     */
    private suspend fun enrich(rootUri: String, row: PhotoAsset, sidecarLastModified: Long?): EnrichmentResult? {
        val metadata = try {
            RawMetadataParser.parse(
                context = appContext,
                uri = Uri.parse(row.fileUri),
                format = row.format,
                companionUri = row.companionFileUri?.let(Uri::parse),
            )
        } catch (e: IOException) {
            ensureSourceAvailable(rootUri)
            return null
        } catch (e: SecurityException) {
            ensureSourceAvailable(rootUri)
            return null
        } catch (e: IllegalArgumentException) {
            ensureSourceAvailable(rootUri)
            return null
        }
        if (!metadata.parsed) ensureSourceAvailable(rootUri)

        return EnrichmentResult(
            metadata = EnrichedMetadata(
                id = row.id,
                dateTaken = metadata.dateTaken ?: row.dateTaken,
                width = metadata.width,
                height = metadata.height,
                cameraMake = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                lensModel = metadata.lensModel,
                shutterSpeed = metadata.shutterSpeed,
                aperture = metadata.aperture,
                iso = metadata.iso,
                focalLength = metadata.focalLength,
                exposureBias = metadata.exposureBias,
                orientation = metadata.orientation,
            ),
            xmp = readSidecar(row, sidecarLastModified),
        )
    }

    private fun ensureSourceAvailable(rootUri: String) {
        if (!SafPermissions.isAvailable(appContext, Uri.parse(rootUri))) {
            throw FileNotFoundException("Source went away during enrichment")
        }
    }

    private suspend fun readSidecar(row: PhotoAsset, sidecarLastModified: Long?): EnrichedXmp? {
        val xmpUri = row.xmpFileUri ?: return null
        if (sidecarLastModified == null || sidecarLastModified <= row.lastModified) return null

        val parsed = XmpSidecarManager.readSidecar(appContext, Uri.parse(xmpUri))
        val isEmpty = parsed.rating == null && parsed.cullStatus == null &&
            parsed.colorLabel == null && parsed.tags.isEmpty()
        if (isEmpty) return null // Unreadable sidecars must not wipe what the row already has.
        return EnrichedXmp(
            rating = parsed.rating,
            cullStatus = parsed.cullStatus,
            colorLabel = parsed.colorLabel,
            tagsJson = converters.fromTagsList(parsed.tags),
            lastModified = sidecarLastModified,
        )
    }

    /** Collects results so that Room, and with it the UI, sees batches rather than single rows. */
    private inner class ResultBatcher {
        private val mutex = Mutex()
        private val buffer = ArrayList<EnrichmentResult>(BATCH_SIZE)

        suspend fun add(result: EnrichmentResult) {
            val full = mutex.withLock {
                buffer += result
                buffer.size >= BATCH_SIZE
            }
            if (full) flush()
        }

        suspend fun flush() {
            val batch = mutex.withLock {
                buffer.toList().also { buffer.clear() }
            }
            if (batch.isNotEmpty()) dao.updateMetadataBatch(batch)
        }
    }

    private companion object {
        const val TAG = "RawCull.Scan"
        const val MAX_CONCURRENT_READS = 3
        const val BATCH_SIZE = 25
        const val FLUSH_INTERVAL_MS = 500L
        const val DB_CHUNK = 500
    }
}
