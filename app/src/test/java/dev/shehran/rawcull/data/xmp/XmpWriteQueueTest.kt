package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.xmp.CullState
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpField
import dev.shehran.rawcull.util.xmp.XmpFixtures
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Behaves like the DAO, including the WRITING guard that protects edits made during a write. */
private class FakeRows : XmpRows {
    val rows = ConcurrentHashMap<String, PhotoAsset>()

    /** Runs inside the write, the way a user's tap can land while the file is being written. */
    fun edit(id: String, bit: Int, change: (PhotoAsset) -> PhotoAsset) {
        rows.compute(id) { _, row ->
            change(row!!).copy(
                xmpSyncStatus = XmpSyncStatus.PENDING,
                xmpPendingFields = row.xmpPendingFields or bit,
            )
        }
    }

    override suspend fun getPhotoById(id: String) = rows[id]

    override suspend fun markWriting(id: String) {
        rows.computeIfPresent(id) { _, row -> row.copy(xmpSyncStatus = XmpSyncStatus.WRITING) }
    }

    override suspend fun finishSynced(id: String, xmpFileUri: String?, mtime: Long?, fileState: CullState?) {
        rows.computeIfPresent(id) { _, row ->
            val located = row.copy(xmpFileUri = xmpFileUri, xmpLastSyncedMtime = mtime)
            if (row.xmpSyncStatus != XmpSyncStatus.WRITING) return@computeIfPresent located
            val merged = fileState?.let {
                located.copy(rating = it.rating, cullStatus = it.cullStatus, colorLabel = it.colorLabel, tags = it.tags)
            } ?: located
            merged.copy(xmpSyncStatus = XmpSyncStatus.SYNCED, xmpError = null, xmpPendingFields = 0)
        }
    }

    override suspend fun finishFailed(id: String, status: XmpSyncStatus, error: String?) {
        rows.computeIfPresent(id) { _, row ->
            if (row.xmpSyncStatus == XmpSyncStatus.WRITING) row.copy(xmpSyncStatus = status, xmpError = error) else row
        }
    }

    override suspend fun recoverableIds(parseError: String) = rows.values.filter {
        it.xmpSyncStatus == XmpSyncStatus.PENDING || it.xmpSyncStatus == XmpSyncStatus.WRITING ||
            (it.xmpSyncStatus == XmpSyncStatus.ERROR && it.xmpError != parseError)
    }.map { it.id }

    override suspend fun markPending(id: String, fields: Int) {
        rows.computeIfPresent(id) { _, row ->
            row.copy(
                xmpSyncStatus = XmpSyncStatus.PENDING,
                xmpError = null,
                xmpPendingFields = row.xmpPendingFields or fields,
            )
        }
    }

    override suspend fun forgetSidecars(id: String, uris: List<String>) {
        rows.computeIfPresent(id) { _, row ->
            row.copy(
                xmpFileUri = row.xmpFileUri.takeUnless { it in uris },
                darktableXmpUri = row.darktableXmpUri.takeUnless { it in uris },
            )
        }
    }
}

private class FakeStore : SidecarStore {
    val files = ConcurrentHashMap<String, ByteArray>()
    val mtimes = ConcurrentHashMap<String, Long>()
    val writes = Collections.synchronizedList(ArrayList<String>())
    val backups = Collections.synchronizedList(ArrayList<String>())
    var onWrite: (String) -> Unit = {}
    var failWith: Exception? = null
    private val clock = AtomicInteger(1000)

    override fun create(asset: PhotoAsset): String {
        failWith?.let { throw it }
        val uri = "store://${asset.sidecarFileName}"
        files[uri] = ByteArray(0)
        return uri
    }

    override fun read(uri: String): SidecarContent? = files[uri]?.let { SidecarContent(it, mtimes[uri]) }

    override fun write(uri: String, bytes: ByteArray): Long? {
        failWith?.let { throw it }
        onWrite(uri)
        writes += uri
        files[uri] = bytes
        val mtime = clock.incrementAndGet().toLong()
        mtimes[uri] = mtime
        return mtime
    }

    override fun backup(uri: String) {
        backups += uri
        files["$uri.bak"] = files.remove(uri)!!
    }

    fun doc(uri: String) = XmpDocument.parse(files.getValue(uri)).getOrThrow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class XmpWriteQueueTest {
    private val rows = FakeRows()
    private val store = FakeStore()
    private val stores = SidecarStores(saf = store, local = store)

    private fun row(id: String = "a", rating: Int = 0, pending: Int = 0, xmp: String? = null) = PhotoAsset(
        id = AssetIds.of("root", "", id),
        rootUri = "root",
        fileName = "$id.ARW",
        fileUri = "content://$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 0L,
        rating = rating,
        xmpFileUri = xmp,
        xmpPendingFields = pending,
        xmpSyncStatus = if (pending != 0) XmpSyncStatus.PENDING else XmpSyncStatus.SYNCED,
    )

    private fun add(row: PhotoAsset): String {
        rows.rows[row.id] = row
        return row.id
    }

    private fun sidecar(uri: String, fixture: String, mtime: Long? = 500L): String {
        store.files[uri] = XmpFixtures.bytes(fixture)
        mtime?.let { store.mtimes[uri] = it }
        return uri
    }

    private fun TestScope.queue(): XmpWriteQueue {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return XmpWriteQueue(
            scope = backgroundScope,
            rows = rows,
            stores = stores,
            prefs = { XmpPrefs() },
            toolkit = "RawCull test",
            ioDispatcher = dispatcher,
            clock = { testScheduler.currentTime },
        ).also { it.start() }
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun tenRapidChangesCostOneWriteWithTheFinalValue() = runTest {
        val id = add(row())
        val queue = queue()
        runCurrent()

        (1..5).forEach { rating ->
            repeat(2) {
                rows.edit(id, XmpField.RATING.bit) { it.copy(rating = rating) }
                queue.enqueue(listOf(id))
                advanceTimeBy(100)
            }
        }
        settle()

        assertEquals(1, store.writes.size)
        assertEquals(5, store.doc(store.writes.single()).rating)
        assertEquals(XmpSyncStatus.SYNCED, rows.rows[id]!!.xmpSyncStatus)
        assertEquals(0, rows.rows[id]!!.xmpPendingFields)
    }

    @Test
    fun nothingIsWrittenBeforeTheQuietPeriodEnds() = runTest {
        val id = add(row(rating = 2, pending = XmpField.RATING.bit))
        val queue = queue()
        runCurrent()
        store.writes.clear()

        queue.enqueue(listOf(id))
        advanceTimeBy(399)
        runCurrent()
        assertTrue(store.writes.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, store.writes.size)
    }

    @Test
    fun aBatchWritesEveryIdAtMostTwoAtATime() {
        val ids = (1..50).map { add(row("p$it", rating = 3, pending = XmpField.RATING.bit)) }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        store.onWrite = {
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            Thread.sleep(5)
            active.decrementAndGet()
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val queue = XmpWriteQueue(scope, rows, stores, { XmpPrefs() }, "RawCull test", Dispatchers.Default, quietMillis = 0)

        queue.enqueue(ids)
        runBlocking { queue.flushNow() }
        scope.cancel()

        assertEquals(50, store.writes.size)
        assertEquals(2, peak.get())
    }

    @Test
    fun flushNowBypassesTheQuietPeriod() = runTest {
        val id = add(row(rating = 4, pending = XmpField.RATING.bit))
        val queue = XmpWriteQueue(
            backgroundScope, rows, stores, { XmpPrefs() }, "RawCull test",
            StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime },
        )

        queue.enqueue(listOf(id))
        queue.flushNow()

        assertEquals(1, store.writes.size)
        assertEquals(XmpSyncStatus.SYNCED, rows.rows[id]!!.xmpSyncStatus)
    }

    @Test
    fun rowsLeftBehindAreWrittenOnStart() = runTest {
        val pending = add(row("p", rating = 1, pending = XmpField.RATING.bit))
        val writing = add(row("w", rating = 2, pending = XmpField.RATING.bit).copy(xmpSyncStatus = XmpSyncStatus.WRITING))
        val failed = add(
            row("e", rating = 3, pending = XmpField.RATING.bit)
                .copy(xmpSyncStatus = XmpSyncStatus.ERROR, xmpError = "Card went away"),
        )
        val unparseable = add(
            row("b", pending = XmpField.RATING.bit, xmp = sidecar("store://b.xmp", XmpFixtures.MALFORMED))
                .copy(xmpSyncStatus = XmpSyncStatus.ERROR, xmpError = XMP_PARSE_ERROR),
        )

        queue()
        settle()

        listOf(pending, writing, failed).forEach { assertEquals(XmpSyncStatus.SYNCED, rows.rows[it]!!.xmpSyncStatus) }
        assertEquals(3, store.writes.size)
        assertEquals(XmpSyncStatus.ERROR, rows.rows[unparseable]!!.xmpSyncStatus)
    }

    @Test
    fun aMalformedSidecarIsNeverWritten() = runTest {
        val uri = sidecar("store://a.xmp", XmpFixtures.MALFORMED)
        val id = add(row(rating = 5, pending = XmpField.RATING.bit, xmp = uri))
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        assertTrue(store.writes.isEmpty())
        assertArrayEquals(XmpFixtures.bytes(XmpFixtures.MALFORMED), store.files[uri])
        val stored = rows.rows[id]!!
        assertEquals(XmpSyncStatus.ERROR, stored.xmpSyncStatus)
        assertEquals(XMP_PARSE_ERROR, stored.xmpError)
        assertEquals("the edit must survive the failure", XmpField.RATING.bit, stored.xmpPendingFields)
    }

    @Test
    fun aMalformedDarktableSidecarStopsBothWrites() = runTest {
        val good = sidecar("store://a.xmp", XmpFixtures.LIGHTROOM)
        val bad = sidecar("store://a.ARW.xmp", XmpFixtures.MALFORMED)
        val id = add(row(rating = 5, pending = XmpField.RATING.bit, xmp = good).copy(darktableXmpUri = bad))
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        assertTrue(store.writes.isEmpty())
        assertEquals(XmpSyncStatus.ERROR, rows.rows[id]!!.xmpSyncStatus)
    }

    @Test
    fun anExternalEditKeepsItsFieldsAndOurPendingFieldWins() = runTest {
        val uri = sidecar("store://a.xmp", XmpFixtures.LIGHTROOM, mtime = 900L)
        val id = add(
            row(rating = 2, pending = XmpField.RATING.bit, xmp = uri)
                .copy(colorLabel = ColorLabel.GREEN, xmpLastSyncedMtime = 100L),
        )
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        val doc = store.doc(uri)
        assertEquals("our pending rating wins", 2, doc.rating)
        assertEquals("their label stays", "Blue", doc.label)
        assertTrue("develop settings stay", "crs:Exposure2012=\"+0.45\"" in store.files[uri]!!.toString(Charsets.UTF_8))

        val stored = rows.rows[id]!!
        assertEquals("the catalog catches up with the file", ColorLabel.BLUE, stored.colorLabel)
        assertEquals(listOf("Wedding", "Dhaka", "Golden Hour"), stored.tags)
        assertEquals(2, stored.rating)
    }

    @Test
    fun aRowTheScanHasNotReachedKeepsTheFilesValues() = runTest {
        val uri = sidecar("store://a.xmp", XmpFixtures.LIGHTROOM)
        val id = add(row(pending = XmpField.PICK.bit, xmp = uri).copy(cullStatus = CullStatus.PICK))
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        val doc = store.doc(uri)
        assertEquals("catalog default of 0 must not replace the file's stars", 4, doc.rating)
        assertEquals(1, doc.pick)
        assertEquals(4, rows.rows[id]!!.rating)
    }

    @Test
    fun anEditDuringAWriteIsNotLost() = runTest {
        val id = add(row(rating = 1, pending = XmpField.RATING.bit))
        val queue = queue()
        runCurrent()
        store.writes.clear()
        var edited = false
        store.onWrite = {
            if (!edited) {
                edited = true
                rows.edit(id, XmpField.RATING.bit) { it.copy(rating = 5) }
                queue.enqueue(listOf(id))
            }
        }

        queue.enqueue(listOf(id))
        settle()

        assertEquals(2, store.writes.size)
        assertEquals(5, store.doc(store.writes.last()).rating)
        assertEquals(XmpSyncStatus.SYNCED, rows.rows[id]!!.xmpSyncStatus)
    }

    @Test
    fun aNewSidecarGetsEveryFieldAndItsUriIsStored() = runTest {
        val id = add(
            row(rating = 3, pending = XmpField.LABEL.bit)
                .copy(colorLabel = ColorLabel.RED, tags = listOf("Kept"), cullStatus = CullStatus.PICK),
        )
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        val stored = rows.rows[id]!!
        val doc = store.doc(stored.xmpFileUri!!)
        assertEquals(3, doc.rating)
        assertEquals("Red", doc.label)
        assertEquals(1, doc.pick)
        assertEquals(listOf("Kept"), doc.subjects)
        assertEquals(store.mtimes[stored.xmpFileUri!!], stored.xmpLastSyncedMtime)
    }

    @Test
    fun anEmptyLeftoverSidecarIsFilledRatherThanReportedAsBroken() = runTest {
        store.files["store://a.xmp"] = ByteArray(0)
        val id = add(row(rating = 3, pending = XmpField.LABEL.bit, xmp = "store://a.xmp"))
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        assertEquals(XmpSyncStatus.SYNCED, rows.rows[id]!!.xmpSyncStatus)
        assertEquals(3, store.doc("store://a.xmp").rating)
    }

    @Test
    fun bothSidecarStylesAreUpdatedWithEachKeepingItsOwnContent() = runTest {
        val adobe = sidecar("store://a.xmp", XmpFixtures.LIGHTROOM)
        val darktable = sidecar("store://a.ARW.xmp", XmpFixtures.DARKTABLE)
        val id = add(row(rating = 2, pending = XmpField.RATING.bit, xmp = adobe).copy(darktableXmpUri = darktable))
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        assertEquals(setOf(adobe, darktable), store.writes.toSet())
        assertTrue("crs:Exposure2012" in store.files[adobe]!!.toString(Charsets.UTF_8))
        assertTrue("darktable:history" in store.files[darktable]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun aSidecarThatIsBothStylesIsWrittenOnce() = runTest {
        val uri = sidecar("store://a.ARW.xmp", XmpFixtures.DARKTABLE)
        val id = add(row(rating = 2, pending = XmpField.RATING.bit, xmp = uri).copy(darktableXmpUri = uri))
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        assertEquals(listOf(uri), store.writes.toList())
    }

    @Test
    fun aReadOnlySourceKeepsTheEditForExport() = runTest {
        val id = add(row(rating = 3, pending = XmpField.RATING.bit))
        store.failWith = SidecarReadOnlyException("Locked")
        val queue = queue()
        runCurrent()

        queue.enqueue(listOf(id))
        settle()

        val stored = rows.rows[id]!!
        assertEquals(XmpSyncStatus.READ_ONLY, stored.xmpSyncStatus)
        assertNull(stored.xmpError)
        assertEquals(XmpField.RATING.bit, stored.xmpPendingFields)
    }

    @Test
    fun anIoFailureIsReportedAndRetrySucceeds() = runTest {
        val id = add(row(rating = 3, pending = XmpField.RATING.bit))
        store.failWith = IOException("Card went away")
        val queue = queue()
        settle()

        val failed = rows.rows[id]!!
        assertEquals(XmpSyncStatus.ERROR, failed.xmpSyncStatus)
        assertEquals("Card went away", failed.xmpError)

        store.failWith = null
        queue.retry(id)
        settle()

        assertEquals(XmpSyncStatus.SYNCED, rows.rows[id]!!.xmpSyncStatus)
        assertNull(rows.rows[id]!!.xmpError)
    }

    @Test
    fun replaceBacksUpTheBadFileThenWritesFromTheCatalog() = runTest {
        val uri = sidecar("store://a.xmp", XmpFixtures.MALFORMED)
        val id = add(
            row(rating = 4, xmp = uri)
                .copy(xmpSyncStatus = XmpSyncStatus.ERROR, xmpError = XMP_PARSE_ERROR, colorLabel = ColorLabel.BLUE),
        )
        val queue = queue()
        runCurrent()

        queue.replaceSidecar(id)
        settle()

        assertEquals(listOf(uri), store.backups.toList())
        assertArrayEquals(XmpFixtures.bytes(XmpFixtures.MALFORMED), store.files["$uri.bak"])
        val stored = rows.rows[id]!!
        assertEquals(XmpSyncStatus.SYNCED, stored.xmpSyncStatus)
        val doc = store.doc(stored.xmpFileUri!!)
        assertEquals(4, doc.rating)
        assertEquals("Blue", doc.label)
    }

    @Test
    fun replaceLeavesAReadableSidecarAlone() = runTest {
        val good = sidecar("store://a.xmp", XmpFixtures.LIGHTROOM)
        val bad = sidecar("store://a.ARW.xmp", XmpFixtures.MALFORMED)
        val id = add(row(xmp = good).copy(darktableXmpUri = bad, xmpSyncStatus = XmpSyncStatus.ERROR))
        val queue = queue()
        runCurrent()

        queue.replaceSidecar(id)
        settle()

        assertEquals(listOf(bad), store.backups.toList())
        assertEquals(good, rows.rows[id]!!.xmpFileUri)
        assertNull(rows.rows[id]!!.darktableXmpUri)
        assertTrue("crs:Exposure2012" in store.files[good]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun retryWithNothingPendingReadsTheFileWithoutWriting() = runTest {
        val uri = sidecar("store://a.xmp", XmpFixtures.LIGHTROOM)
        val id = add(row(xmp = uri).copy(xmpSyncStatus = XmpSyncStatus.ERROR, xmpError = XMP_PARSE_ERROR))
        val queue = queue()
        runCurrent()

        queue.retry(id)
        settle()

        assertTrue(store.writes.isEmpty())
        val stored = rows.rows[id]!!
        assertEquals(XmpSyncStatus.SYNCED, stored.xmpSyncStatus)
        assertEquals(4, stored.rating)
        assertFalse(stored.xmpError != null)
    }
}
