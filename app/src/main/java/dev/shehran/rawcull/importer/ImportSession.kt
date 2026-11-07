package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.scan.AssetDraft
import dev.shehran.rawcull.data.scan.AssetGrouper
import dev.shehran.rawcull.data.scan.WalkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.shehran.rawcull.library.PlausibleDate
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** What the card scan learned about a file's asset. */
data class SourceMetadata(val captureDate: Long?, val metadataJson: String?)

/** The source side of a scan, kept apart so the session can be tested without a real tree. */
interface ImportSourceReader {
    @Throws(IOException::class)
    suspend fun list(treeUri: String, onProgress: (dirs: Int, files: Int) -> Unit): WalkResult

    /** @throws IOException if the file can't be opened, which may mean the volume is gone. */
    @Throws(IOException::class)
    fun metadata(draft: AssetDraft): SourceMetadata

    /** Null if the file can't be read. */
    fun headHash(uri: String): String?

    fun isAvailable(treeUri: String): Boolean
}

sealed interface ImportSessionState {
    data object Idle : ImportSessionState
    data class Scanning(val dirs: Int, val files: Int) : ImportSessionState

    /** Candidates are usable already; dates and duplicate status are still filling in. */
    data class Reading(val done: Int, val total: Int, val candidates: List<ImportCandidate>) : ImportSessionState
    data class Ready(val candidates: List<ImportCandidate>) : ImportSessionState
    data object SourceLost : ImportSessionState
    data object Empty : ImportSessionState

    val candidateList: List<ImportCandidate>
        get() = when (this) {
            is Reading -> candidates
            is Ready -> candidates
            else -> emptyList()
        }
}

/**
 * What's on the source right now. It lives in memory only (D7): the card is a source, not a
 * library, so nothing here reaches the catalog, and only the import job outlives the process.
 *
 * @param knownSizes every size in the duplicate index.
 * @param isIndexed whether the index has that head hash and size.
 */
class ImportSession(
    private val reader: ImportSourceReader,
    private val knownSizes: suspend () -> Set<Long>,
    private val isIndexed: suspend (headHash: String, sizeBytes: Long) -> Boolean,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<ImportSessionState>(ImportSessionState.Idle)
    val state: StateFlow<ImportSessionState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var source: ImportSource? = null

    /** Scans [next], replacing whatever was being looked at. */
    @Synchronized
    fun start(next: ImportSource) {
        source = next
        launchScan(next)
    }

    private fun launchScan(target: ImportSource) {
        val previous = job
        previous?.cancel()
        job = scope.launch(ioDispatcher) {
            previous?.join()
            scan(target)
        }
    }

    /** Forgets the source, ready for a new import. */
    @Synchronized
    fun clear() {
        job?.cancel()
        job = null
        source = null
        _state.value = ImportSessionState.Idle
    }

    /** A volume came or went: losing the source shows at once, and getting it back rescans it. */
    fun onStorageChanged() {
        val target = source ?: return
        scope.launch(ioDispatcher) {
            val available = reader.isAvailable(target.treeUri)
            synchronized(this@ImportSession) {
                if (source != target) return@launch
                val current = _state.value
                when {
                    available && current == ImportSessionState.SourceLost -> launchScan(target)
                    !available && current != ImportSessionState.SourceLost -> {
                        job?.cancel()
                        _state.value = ImportSessionState.SourceLost
                    }
                }
            }
        }
    }

    private suspend fun scan(target: ImportSource) {
        try {
            _state.value = ImportSessionState.Scanning(0, 0)
            val started = clock()
            val walk = reader.list(target.treeUri) { dirs, files ->
                _state.value = ImportSessionState.Scanning(dirs, files)
            }
            val walkMillis = clock() - started

            val assets = AssetGrouper.group(walk.entries).assets
            if (assets.isEmpty()) {
                // An empty listing is only believable if the volume is still there.
                val available = reader.isAvailable(target.treeUri)
                _state.value = if (available) ImportSessionState.Empty else ImportSessionState.SourceLost
                return
            }

            readCandidates(target, assets, walk.entries.size, walkMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            onReadFailed(target, e)
        } catch (e: SecurityException) {
            onReadFailed(target, e)
        } catch (e: IllegalArgumentException) {
            onReadFailed(target, e)
        } catch (e: IllegalStateException) {
            onReadFailed(target, e) // Providers can surface a dying volume this way.
        }
    }

    private fun onReadFailed(target: ImportSource, cause: Exception) {
        log("scan failed for ${target.label}: $cause")
        _state.value = ImportSessionState.SourceLost
    }

    private suspend fun readCandidates(
        target: ImportSource,
        assets: List<AssetDraft>,
        files: Int,
        walkMillis: Long,
    ) {
        val started = clock()
        // Newest first, so the shoot just taken is what fills in while the rest is being read.
        val drafts = assets.sortedByDescending { it.primary.lastModified }
        val candidates = drafts.map { it.toCandidate(target.treeUri) }.toTypedArray()
        val total = candidates.size
        val done = AtomicInteger(0)
        val duplicates = DuplicateCheck(knownSizes(), isIndexed)

        fun snapshot() = synchronized(candidates) { candidates.toList() }
        _state.value = ImportSessionState.Reading(0, total, snapshot())

        coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_READS)
            drafts.forEachIndexed { index, draft ->
                launch {
                    semaphore.withPermit {
                        val read = readOne(target, draft, candidates[index], duplicates)
                        synchronized(candidates) { candidates[index] = read }
                        val count = done.incrementAndGet()
                        if (count % PUBLISH_EVERY == 0 && count < total) {
                            _state.value = ImportSessionState.Reading(count, total, snapshot())
                        }
                    }
                }
            }
        }

        _state.value = ImportSessionState.Ready(snapshot())
        log(
            "scan walkMs=$walkMillis files=$files assets=$total datesMs=${clock() - started} " +
                "hashesComputed=${duplicates.hashesComputed} hashesSkipped=${duplicates.hashesSkipped}",
        )
    }

    private suspend fun readOne(
        target: ImportSource,
        draft: AssetDraft,
        candidate: ImportCandidate,
        duplicates: DuplicateCheck,
    ): ImportCandidate {
        val metadata = try {
            reader.metadata(draft)
        } catch (e: IOException) {
            ensureAvailable(target, e)
            return candidate // The file is bad but the card is fine: the file date will do.
        } catch (e: SecurityException) {
            ensureAvailable(target, e)
            return candidate
        }

        val files = candidate.files.map { file ->
            if (file.role.isSidecar) return@map file
            file.copy(alreadyImported = duplicates.isImported(file.sizeBytes) { reader.headHash(file.uri) })
        }
        // A camera with an unset clock mustn't file photos under 1970: the file date gets its turn.
        val captureDate = metadata.captureDate.takeIf { PlausibleDate.isPlausible(it, wallClock()) }
        return candidate.copy(captureDate = captureDate, metadataJson = metadata.metadataJson, files = files)
    }

    private fun ensureAvailable(target: ImportSource, cause: Exception) {
        if (!reader.isAvailable(target.treeUri)) throw IOException("Source went away", cause)
    }

    private fun AssetDraft.toCandidate(treeUri: String): ImportCandidate {
        val files = buildList {
            add(CandidateFile(primary.docUri, primary.name, primary.size, ImportItemRole.PRIMARY, format))
            companion?.let { add(CandidateFile(it.docUri, it.name, it.size, ImportItemRole.COMPANION, companionFormat)) }
            adobeXmp?.let { add(CandidateFile(it.docUri, it.name, it.size, ImportItemRole.ADOBE_SIDECAR)) }
            darktableXmp?.let { add(CandidateFile(it.docUri, it.name, it.size, ImportItemRole.DARKTABLE_SIDECAR)) }
        }
        return ImportCandidate(
            id = idIn(treeUri),
            stemGroup = "${primary.parentDocId}|${stem.lowercase(Locale.ROOT)}",
            stem = stem,
            files = files,
            fallbackDate = primary.lastModified.takeIf { PlausibleDate.isPlausible(it, wallClock()) },
        )
    }

    private companion object {
        const val MAX_CONCURRENT_READS = 3
        const val PUBLISH_EVERY = 25
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
