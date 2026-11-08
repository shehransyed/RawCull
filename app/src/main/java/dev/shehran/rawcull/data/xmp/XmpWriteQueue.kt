package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.location
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.xmp.CullState
import dev.shehran.rawcull.util.xmp.ParsedCullState
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpField
import dev.shehran.rawcull.util.xmp.XmpMapper
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Date

/** Shown to the user, and how a parse failure is told apart from errors worth retrying on their own. */
const val XMP_PARSE_ERROR = "Existing sidecar isn't valid XMP; not overwritten"

/** What the queue needs from the catalog. */
interface XmpRows {
    suspend fun getPhotoById(id: String): PhotoAsset?

    /** Must happen before the row is loaded: an edit after that point moves it back to PENDING. */
    suspend fun markWriting(id: String)

    /**
     * Records the sidecar, then completes the sync unless the row was edited during the write. In
     * that case it stays PENDING, and the edit's own enqueue writes it again.
     */
    suspend fun finishSynced(id: String, xmpFileUri: String?, mtime: Long?, fileState: CullState?)

    /** Like [finishSynced], the outcome is dropped if the row was edited during the write. */
    suspend fun finishFailed(id: String, status: XmpSyncStatus, error: String?)

    /** Rows with changes that may not be on disk. Parse errors stay out: retrying can't fix them. */
    suspend fun recoverableIds(parseError: String): List<String>

    /** Marks a row for another write, adding [fields] to what it has pending. */
    suspend fun markPending(id: String, fields: Int)

    /** Forgets sidecars that have been moved aside, so that a new one gets created. */
    suspend fun forgetSidecars(id: String, uris: List<String>)
}

/**
 * Writes catalog changes to sidecars in the background. Each id waits for a quiet period, so a burst
 * of edits to one photo costs one write, and the write always uses the row's latest values.
 *
 * A row's `xmpPendingFields` lists what the user changed. Only those fields are written, and every
 * other field is taken from the file. That is the conflict rule, and it also protects rows whose
 * sidecar the scan hasn't reached yet, whose catalog values are still defaults.
 */
class XmpWriteQueue(
    private val scope: CoroutineScope,
    private val rows: XmpRows,
    private val stores: SidecarStores,
    private val prefs: suspend () -> XmpPrefs,
    private val toolkit: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val quietMillis: Long = QUIET_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val now: () -> Date = { Date() },
    private val log: (String) -> Unit = {},
) {
    private val dueAt = LinkedHashMap<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Held while writing, so that a flush can't process an id the worker is already on. */
    private val writing = Mutex()

    /** Starts the worker and picks up whatever a previous process left unwritten. */
    fun start() {
        scope.launch(ioDispatcher) {
            recover()
            runWorker()
        }
    }

    fun enqueue(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val due = clock() + quietMillis
        synchronized(dueAt) { ids.forEach { dueAt[it] = due } }
        wake.trySend(Unit)
    }

    /** Writes everything queued now, ignoring the quiet period, and can't be cancelled part-way. */
    suspend fun flushNow() = withContext(NonCancellable + ioDispatcher) {
        val ids = synchronized(dueAt) { dueAt.keys.toList().also { dueAt.clear() } }
        writing.withLock { process(ids) }
    }

    suspend fun recover() {
        enqueue(rows.recoverableIds(XMP_PARSE_ERROR))
    }

    suspend fun retry(id: String) {
        rows.markPending(id, fields = 0)
        enqueue(listOf(id))
    }

    /**
     * Moves the sidecars that can't be parsed aside to `.bak` and writes a new one from the catalog.
     * Sidecars that are fine stay as they are.
     */
    suspend fun replaceSidecar(id: String) {
        withContext(ioDispatcher) {
            writing.withLock {
                val row = rows.getPhotoById(id) ?: return@withLock
                val store = stores.forRoot(row.rootUri)
                val unreadable = row.sidecarUris().filter { uri ->
                    store.read(uri)?.takeUnless { it.isBlank() }?.let { parse(it).isFailure } ?: false
                }
                unreadable.forEach(store::backup)
                rows.forgetSidecars(id, unreadable)
                rows.markPending(id, XmpField.ALL_BITS)
            }
        }
        enqueue(listOf(id))
    }

    private suspend fun runWorker() = coroutineScope {
        while (isActive) {
            val ids = takeDue()
            if (ids.isNotEmpty()) {
                writing.withLock { process(ids) }
                continue
            }
            val wait = millisUntilNextDue()
            if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private fun takeDue(): List<String> = synchronized(dueAt) {
        val time = clock()
        val due = dueAt.filterValues { it <= time }.keys.toList()
        due.forEach(dueAt::remove)
        due
    }

    private fun millisUntilNextDue(): Long? = synchronized(dueAt) {
        dueAt.values.minOrNull()?.let { (it - clock()).coerceAtLeast(1L) }
    }

    private suspend fun process(ids: List<String>) = coroutineScope {
        val permits = Semaphore(MAX_CONCURRENT_WRITES)
        ids.forEach { id -> launch { permits.withPermit { sync(id) } } }
    }

    private suspend fun sync(id: String) {
        rows.markWriting(id)
        val row = rows.getPhotoById(id) ?: return
        try {
            write(row)
        } catch (e: CancellationException) {
            throw e // The row stays WRITING, and the next start picks it up.
        } catch (e: UnreadableSidecarException) {
            rows.finishFailed(id, XmpSyncStatus.ERROR, XMP_PARSE_ERROR)
        } catch (e: SidecarReadOnlyException) {
            rows.finishFailed(id, XmpSyncStatus.READ_ONLY, null)
        } catch (e: IOException) {
            rows.finishFailed(id, XmpSyncStatus.ERROR, e.message ?: "Couldn't write the sidecar")
        } catch (e: IllegalArgumentException) {
            rows.finishFailed(id, XmpSyncStatus.ERROR, e.message ?: "Couldn't write the sidecar")
        } catch (e: IllegalStateException) {
            rows.finishFailed(id, XmpSyncStatus.ERROR, e.message ?: "Couldn't write the sidecar")
        }
    }

    private suspend fun write(row: PhotoAsset) {
        val store = stores.forRoot(row.rootUri)
        val settings = prefs()
        val pending = XmpField.fromBits(row.xmpPendingFields)
        val state = row.cullState()

        // Parse every sidecar before writing any, so that a bad one can't leave them disagreeing.
        val main = row.xmpFileUri?.let { uri -> store.read(uri)?.let { Target(uri, it) } }
        val extra = row.darktableXmpUri?.takeIf { it != row.xmpFileUri }
            ?.let { uri -> store.read(uri)?.let { Target(uri, it) } }
        val mainDoc = main?.content?.takeUnless { it.isBlank() }?.let { parseOrThrow(it) }
        val extraDoc = extra?.content?.takeUnless { it.isBlank() }?.let { parseOrThrow(it) }

        if (mainDoc != null && pending.isEmpty()) {
            rows.finishSynced(row.id, main.uri, main.content.lastModified, XmpMapper.read(mainDoc).merge(state, pending))
            return
        }

        if (mainDoc != null && isExternallyChanged(row, main.content)) {
            log("${row.fileName}: sidecar changed outside RawCull; merging pending $pending")
        }

        // A new or empty sidecar has nothing of its own, so the catalog supplies every field.
        val doc = mainDoc ?: XmpDocument.newEmpty(toolkit)
        XmpMapper.apply(doc, state, settings, if (mainDoc != null) pending else newSidecarFields(pending), now())

        val uri = main?.uri ?: store.create(row)
        val mtime = store.write(uri, doc.toBytes())
        if (extra != null) {
            val merged = extraDoc ?: XmpDocument.newEmpty(toolkit)
            XmpMapper.apply(merged, state, settings, if (extraDoc != null) pending else newSidecarFields(pending), now())
            store.write(extra.uri, merged.toBytes())
        }
        rows.finishSynced(row.id, uri, mtime, mainDoc?.let { XmpMapper.read(it).merge(state, pending) })
    }

    private fun isExternallyChanged(row: PhotoAsset, content: SidecarContent): Boolean {
        val synced = row.xmpLastSyncedMtime ?: return false
        return (content.lastModified ?: return false) > synced
    }

    private fun parse(content: SidecarContent) = XmpDocument.parse(content.bytes)

    private fun parseOrThrow(content: SidecarContent): XmpDocument =
        parse(content).getOrElse { throw UnreadableSidecarException() }

    private class Target(val uri: String, val content: SidecarContent)

    private class UnreadableSidecarException : Exception()

    private companion object {
        const val QUIET_MILLIS = 400L
        const val MAX_CONCURRENT_WRITES = 2
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** A new sidecar takes every field except a location the user hasn't edited (which may be the camera's). */
private fun newSidecarFields(pending: Set<XmpField>): Set<XmpField> = XmpField.NEW_SIDECAR + (pending intersect setOf(XmpField.GPS))

/** A crash between creating a sidecar and writing it leaves an empty file, which holds nothing to keep. */
private fun SidecarContent.isBlank(): Boolean = bytes.all { it.toInt().toChar().isWhitespace() }

private fun PhotoAsset.sidecarUris(): List<String> = listOfNotNull(xmpFileUri, darktableXmpUri).distinct()

private fun PhotoAsset.cullState() = CullState(rating, cullStatus, colorLabel, tags, location)

/** The catalog state after a sync: pending fields as the user set them, the rest as the file has them. */
private fun ParsedCullState.merge(state: CullState, pending: Set<XmpField>) = CullState(
    rating = if (XmpField.RATING in pending) state.rating else rating,
    cullStatus = if (XmpField.PICK in pending) state.cullStatus else cullStatus ?: state.cullStatus,
    colorLabel = if (XmpField.LABEL in pending) state.colorLabel else colorLabel,
    tags = if (XmpField.TAGS in pending) state.tags else tags,
    location = if (XmpField.GPS in pending) state.location else location ?: state.location,
)
