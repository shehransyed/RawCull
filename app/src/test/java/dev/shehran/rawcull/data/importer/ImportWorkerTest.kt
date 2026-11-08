package dev.shehran.rawcull.data.importer

import android.app.Notification
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.repository.CollectionChange
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.VerifyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** The worker over the real engine and Room, with only the documents faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tree = "tree://library"
    private val cardTree = "tree://card"

    private lateinit var database: AppDatabase
    private lateinit var engine: ImportEngine
    private val io = FakeDocumentIo()
    private var locationId = 0L
    private val finished = ArrayList<ImportJob>()
    private lateinit var cardDir: String

    private val notifier = object : ImportNotifier {
        override fun foregroundInfo(workId: UUID, job: ImportJob?, currentFile: String?, bytesPerSecond: Long) =
            ForegroundInfo(1, Notification())

        override fun notifyFinished(job: ImportJob) {
            finished += job
        }
    }

    private lateinit var collections: CollectionRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        collections = CollectionRepository(database)
        engine = ImportEngine(
            jobs = database.importJobDao(),
            importedFiles = database.importedFileDao(),
            locations = database.locationDao(),
            photos = database.rawPhotoDao(),
            io = io,
            collections = collections,
            ioDispatcher = Dispatchers.Unconfined,
        )
        locationId = database.locationDao().insert(
            LibraryLocation(treeUri = tree, displayName = "Photos", addedAt = 0, lastOpenedAt = 0),
        )
        cardDir = io.root(cardTree)
        io.root(tree)
    }

    @After
    fun tearDown() = database.close()

    private fun bytes(size: Int, seed: Int = 1) = ByteArray(size) { ((it + seed) * 31 % 251).toByte() }

    private fun item(
        name: String,
        group: String = name.substringBefore('.'),
        role: ImportItemRole = ImportItemRole.PRIMARY,
        content: ByteArray = bytes(200_000, name.hashCode()),
        dir: String = "2026/2026-11-06",
        targetName: String = name,
        state: ImportItemState = ImportItemState.QUEUED,
        metadataJson: String? = null,
    ) = ImportJobItem(
        jobId = 0,
        groupKey = group,
        sourceUri = io.addFile(cardDir, name, content),
        sourceName = name,
        sizeBytes = content.size.toLong(),
        targetRelativeDir = dir,
        targetName = targetName,
        state = state,
        role = role,
        captureDate = 5_000L,
        metadataJson = metadataJson,
    )

    private suspend fun job(
        items: List<ImportJobItem>,
        verifyMode: VerifyMode = VerifyMode.FULL,
        deleteSource: Boolean = false,
        collectionId: Long? = null,
    ): Long = database.importJobDao().insertJobWithItems(
        ImportJob(
            createdAt = 1,
            sourceTreeUri = cardTree,
            sourceLabel = "SD card",
            locationId = locationId,
            template = "2026/2026-11-06",
            verifyMode = verifyMode,
            deleteSourceAfter = deleteSource,
            totalFiles = items.count { it.state != ImportItemState.SKIPPED_DUPLICATE },
            totalBytes = items.sumOf { it.sizeBytes },
            collectionId = collectionId,
        ),
        items,
    )

    private fun worker(jobId: Long): ImportWorker =
        TestListenableWorkerBuilder<ImportWorker>(context)
            .setInputData(Data.Builder().putLong(ImportWorker.KEY_JOB_ID, jobId).build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ImportWorker(
                    appContext,
                    workerParameters,
                    engine,
                    { database.importJobDao().getJob(it) },
                    notifier,
                )
            })
            .build()

    private suspend fun run(jobId: Long): ImportJob {
        val result = worker(jobId).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        return checkNotNull(database.importJobDao().getJob(jobId))
    }

    private suspend fun items(jobId: Long) = database.importJobDao().getItems(jobId).associateBy { it.sourceName }

    private fun library() = io.files(io.root(tree))

    @Test
    fun `files land under their folder with identical content`() = runBlocking<Unit> {
        val raw = item("DSC1.ARW")
        val jpeg = item("DSC1.JPG", group = "DSC1", role = ImportItemRole.COMPANION)
        val jobId = job(listOf(raw, jpeg))
        val sources = io.files(cardDir)

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(2, job.doneFiles)
        assertEquals(raw.sizeBytes + jpeg.sizeBytes, job.doneBytes)
        assertNotNull(job.finishedAt)
        assertArrayEquals(sources.getValue("DSC1.ARW"), library().getValue("2026/2026-11-06/DSC1.ARW"))
        assertArrayEquals(sources.getValue("DSC1.JPG"), library().getValue("2026/2026-11-06/DSC1.JPG"))
        assertEquals(setOf(ImportItemState.DONE), items(jobId).values.map { it.state }.toSet())
    }

    @Test
    fun `active time carries over from an earlier run`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        database.importJobDao().updateProgress(jobId, 0, 0, 0, 0, activeMillis = 5_000)

        val job = run(jobId)

        assertTrue(job.activeMillis >= 5_000)
        assertTrue(job.activeMillis < 60_000)
    }

    @Test
    fun `the finished job is reported`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        run(jobId)
        assertEquals(listOf(jobId), finished.map { it.id })
    }

    @Test
    fun `hashes are recorded on the item`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        run(jobId)
        val done = items(jobId).getValue("A.ARW")
        assertEquals(64, done.fullHash?.length)
        assertEquals(64, done.headHash?.length)
    }

    @Test
    fun `images are indexed as imported and sidecars are not`() = runBlocking<Unit> {
        val raw = item("A.ARW")
        val sidecar = item("A.xmp", group = "A", role = ImportItemRole.ADOBE_SIDECAR, content = xmp(3))
        run(job(listOf(raw, sidecar)))

        assertEquals(listOf(raw.sizeBytes), database.importedFileDao().knownSizes())
    }

    @Test
    fun `a library row appears with the job, EXIF and pairing`() = runBlocking<Unit> {
        val metadata = """{"model":"ILCE-7M4","date":9000,"width":100,"height":50,"orientation":1}"""
        val jobId = job(
            listOf(
                item("A.ARW", metadataJson = metadata),
                item("A.JPG", group = "A", role = ImportItemRole.COMPANION),
            ),
        )

        run(jobId)

        val row = database.rawPhotoDao().observeByImportJob(jobId).first().single()
        assertEquals("2026/2026-11-06", row.relativeDir)
        assertEquals(tree, row.rootUri)
        assertEquals("ILCE-7M4", row.cameraModel)
        assertEquals(9000L, row.dateTaken)
        assertTrue(row.metadataLoaded)
        assertEquals(PairMode.RAW_AND_RASTER, row.pairMode)
        assertEquals("A.JPG", row.companionFileName)
    }

    @Test
    fun `without EXIF the row is left for the scan to read`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        run(jobId)
        assertFalse(database.rawPhotoDao().observeByImportJob(jobId).first().single().metadataLoaded)
    }

    @Test
    fun `a copied sidecar's rating shows at once and is recorded as in sync`() = runBlocking<Unit> {
        val jobId = job(
            listOf(
                item("A.ARW", metadataJson = "{}"),
                item("A.xmp", group = "A", role = ImportItemRole.ADOBE_SIDECAR, content = xmp(4)),
            ),
        )

        run(jobId)

        val row = database.rawPhotoDao().observeByImportJob(jobId).first().single()
        assertEquals(4, row.rating)
        assertEquals(ColorLabel.NONE, row.colorLabel)
        assertEquals(CullStatus.UNFLAGGED, row.cullStatus)
        assertEquals(items(jobId).getValue("A.xmp").targetUri, row.xmpFileUri)
        assertNotNull(row.xmpLastSyncedMtime)
    }

    private fun xmp(rating: Int) =
        ("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
            .toByteArray()

    @Test
    fun `quick verification also lands good files`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")), verifyMode = VerifyMode.QUICK)
        assertEquals(ImportJobStatus.COMPLETED, run(jobId).status)
    }

    @Test
    fun `resume skips what is done and restarts what was mid-copy`() = runBlocking<Unit> {
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val finished = io.addFile(dir, "DONE.ARW", bytes(20))
        val done = item("DONE.ARW", state = ImportItemState.DONE).copy(targetUri = finished, sizeBytes = 20L)
        val midCopy = item("MID.ARW")
        val jobId = job(listOf(done, midCopy))

        // What a kill mid-file leaves: an item marked COPYING and a truncated target.
        val partial = io.addFile(dir, "MID.ARW", ByteArray(10))
        val killed = items(jobId).getValue("MID.ARW")
        database.importJobDao().updateItem(killed.copy(state = ImportItemState.COPYING, targetUri = partial))

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertFalse(io.exists(partial))
        assertEquals(killed.sizeBytes, library().getValue("2026/2026-11-06/MID.ARW").size.toLong())
        assertEquals(2, job.doneFiles)
        assertEquals(2, library().size)
    }

    @Test
    fun `a kill between the last file and its row still gets the row written, with its folder`() = runBlocking<Unit> {
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val finished = io.addFile(dir, "A.ARW", bytes(20))
        val done = item("A.ARW", state = ImportItemState.DONE).copy(targetUri = finished, sizeBytes = 20L)
        val jobId = job(listOf(done))

        run(jobId)

        val row = database.rawPhotoDao().observeByImportJob(jobId).first().single()
        assertEquals(dir, row.parentDocUri)
    }

    @Test
    fun `resuming leaves the rows of finished groups alone`() = runBlocking<Unit> {
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val finished = io.addFile(dir, "A.ARW", bytes(20))
        val done = item("A.ARW", state = ImportItemState.DONE).copy(targetUri = finished, sizeBytes = 20L)
        val jobId = job(listOf(done))
        run(jobId)
        val dao = database.rawPhotoDao()
        val row = dao.observeByImportJob(jobId).first().single()
        dao.upsertAll(listOf(row.copy(rating = 5)))

        run(jobId)

        assertEquals(5, dao.getPhotoById(row.id)?.rating)
    }

    @Test
    fun `a target created but never recorded before the kill is reused, not duplicated`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        io.addFile(dir, "A.ARW", ByteArray(0))
        val killed = items(jobId).getValue("A.ARW")
        database.importJobDao().updateItem(killed.copy(state = ImportItemState.COPYING, targetUri = null))

        run(jobId)

        assertEquals(setOf("2026/2026-11-06/A.ARW"), library().keys)
        assertEquals(killed.sizeBytes, library().values.single().size.toLong())
    }

    @Test
    fun `a file that isn't ours is never removed on resume`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val theirs = bytes(50, 7)
        io.addFile(dir, "A.ARW", theirs)
        val killed = items(jobId).getValue("A.ARW")
        database.importJobDao().updateItem(killed.copy(state = ImportItemState.COPYING, targetUri = null))

        run(jobId)

        assertArrayEquals(theirs, library().getValue("2026/2026-11-06/A.ARW"))
        assertEquals(killed.sizeBytes, library().getValue("2026/2026-11-06/A-1.ARW").size.toLong())
    }

    @Test
    fun `a verify mismatch is retried and succeeds`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        io.corruptWrites["A.ARW"] = 1
        val sources = io.files(cardDir)

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertArrayEquals(sources.getValue("A.ARW"), library().getValue("2026/2026-11-06/A.ARW"))
        assertEquals(1, library().size)
    }

    @Test
    fun `a second mismatch fails that file and the job goes on`() = runBlocking<Unit> {
        val jobId = job(listOf(item("BAD.ARW"), item("GOOD.ARW")))
        io.corruptWrites["BAD.ARW"] = 2

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED_WITH_ERRORS, job.status)
        assertEquals(1, job.failedFiles)
        assertEquals(1, job.doneFiles)
        val bad = items(jobId).getValue("BAD.ARW")
        assertEquals(ImportItemState.FAILED, bad.state)
        assertEquals("The copy didn't match the source", bad.error)
        assertNull(bad.targetUri)
        assertEquals(setOf("2026/2026-11-06/GOOD.ARW"), library().keys)
    }

    @Test
    fun `quick verification catches a corrupt head`() = runBlocking<Unit> {
        val small = bytes(1000)
        val jobId = job(listOf(item("A.ARW", content = small)), verifyMode = VerifyMode.QUICK)
        io.corruptWrites["A.ARW"] = 2

        assertEquals(ImportJobStatus.COMPLETED_WITH_ERRORS, run(jobId).status)
        assertTrue(library().isEmpty())
    }

    @Test
    fun `a short read fails verification rather than landing a truncated file`() = runBlocking<Unit> {
        val item = item("A.ARW").let { it.copy(sizeBytes = it.sizeBytes + 1) }
        val jobId = job(listOf(item))

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED_WITH_ERRORS, job.status)
        assertTrue(library().isEmpty())
    }

    @Test
    fun `a pulled card stops as source lost with the rest still queued`() = runBlocking<Unit> {
        val first = item("A.ARW")
        val second = item("B.ARW")
        val third = item("C.ARW")
        val jobId = job(listOf(first, second, third))
        io.onSourceRead = { name ->
            if (name == "B.ARW") {
                io.sourceAvailable = false
                io.unreadable += second.sourceUri
            }
        }

        val job = run(jobId)

        assertEquals(ImportJobStatus.SOURCE_LOST, job.status)
        val states = items(jobId)
        assertEquals(ImportItemState.DONE, states.getValue("A.ARW").state)
        assertEquals(ImportItemState.QUEUED, states.getValue("B.ARW").state)
        assertEquals(ImportItemState.QUEUED, states.getValue("C.ARW").state)
        assertEquals(setOf("2026/2026-11-06/A.ARW"), library().keys)
    }

    @Test
    fun `resuming after the card returns finishes the job`() = runBlocking<Unit> {
        val second = item("B.ARW")
        val jobId = job(listOf(item("A.ARW"), second))
        io.onSourceRead = { name ->
            if (name == "B.ARW") {
                io.sourceAvailable = false
                io.unreadable += second.sourceUri
            }
        }
        run(jobId)

        io.onSourceRead = null
        io.sourceAvailable = true
        io.unreadable.clear()
        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(2, library().size)
        assertEquals(2, job.doneFiles)
    }

    @Test
    fun `one unreadable file on a healthy card fails alone`() = runBlocking<Unit> {
        val bad = item("BAD.ARW")
        val jobId = job(listOf(bad, item("GOOD.ARW")))
        io.unreadable += bad.sourceUri

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED_WITH_ERRORS, job.status)
        assertEquals(setOf("2026/2026-11-06/GOOD.ARW"), library().keys)
        assertTrue(items(jobId).getValue("BAD.ARW").error!!.startsWith("Couldn't read the file"))
    }

    @Test
    fun `a full destination fails what's left with a clear reason`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW"), item("B.ARW")))
        io.writeFailure = "write failed: ENOSPC (No space left on device)"

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED_WITH_ERRORS, job.status)
        assertEquals(2, job.failedFiles)
        assertEquals(setOf("Not enough space"), items(jobId).values.map { it.error }.toSet())
        assertTrue(library().isEmpty())
    }

    @Test
    fun `losing the destination pauses the job instead of failing every file`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW"), item("B.ARW")))
        io.writeFailure = "I/O error"
        io.destinationAvailable = false

        val job = run(jobId)

        assertEquals(ImportJobStatus.PAUSED, job.status)
        assertEquals(setOf(ImportItemState.QUEUED), items(jobId).values.map { it.state }.toSet())
    }

    @Test
    fun `duplicates planned as skipped are not copied`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW", state = ImportItemState.SKIPPED_DUPLICATE), item("B.ARW")))

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(1, job.skippedFiles)
        assertEquals(1, job.doneFiles)
        assertEquals(setOf("2026/2026-11-06/B.ARW"), library().keys)
    }

    @Test
    fun `a name taken since planning moves the whole group to the next suffix`() = runBlocking<Unit> {
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val theirs = bytes(10, 9)
        io.addFile(dir, "A.ARW", theirs)
        val jobId = job(
            listOf(
                item("A.ARW"),
                item("A.JPG", group = "A", role = ImportItemRole.COMPANION),
                item("A.xmp", group = "A", role = ImportItemRole.ADOBE_SIDECAR, content = xmp(1)),
            ),
        )

        run(jobId)

        assertEquals(
            setOf("2026/2026-11-06/A.ARW", "2026/2026-11-06/A-1.ARW", "2026/2026-11-06/A-1.JPG", "2026/2026-11-06/A-1.xmp"),
            library().keys,
        )
        assertArrayEquals(theirs, library().getValue("2026/2026-11-06/A.ARW"))
        assertEquals("A-1.ARW", database.rawPhotoDao().observeByImportJob(jobId).first().single().fileName)
    }

    @Test
    fun `a provider de-duplicating a name counts as a conflict`() = runBlocking<Unit> {
        io.forceRename += "A.ARW"
        val jobId = job(listOf(item("A.ARW")))

        run(jobId)

        assertEquals(setOf("2026/2026-11-06/A-1.ARW"), library().keys)
    }

    @Test
    fun `a companion conflict renames the primary already copied`() = runBlocking<Unit> {
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        io.addFile(dir, "A.JPG", bytes(10, 9))
        val jobId = job(listOf(item("A.ARW"), item("A.JPG", group = "A", role = ImportItemRole.COMPANION)))

        run(jobId)

        assertEquals(
            setOf("2026/2026-11-06/A.JPG", "2026/2026-11-06/A-1.ARW", "2026/2026-11-06/A-1.JPG"),
            library().keys,
        )
    }

    @Test
    fun `a library sidecar that appeared since planning is kept`() = runBlocking<Unit> {
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val existing = xmp(5)
        io.addFile(dir, "A.xmp", existing)
        val jobId = job(listOf(item("A.ARW"), item("A.xmp", group = "A", role = ImportItemRole.ADOBE_SIDECAR, content = xmp(1))))

        run(jobId)

        assertArrayEquals(existing, library().getValue("2026/2026-11-06/A.xmp"))
        assertEquals(ImportItemState.SKIPPED_DUPLICATE, items(jobId).getValue("A.xmp").state)
    }

    @Test
    fun `verified groups are deleted from the card`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW"), item("A.JPG", group = "A", role = ImportItemRole.COMPANION)), deleteSource = true)

        run(jobId)

        assertTrue(io.files(cardDir).isEmpty())
        assertTrue(items(jobId).values.all { it.sourceDeleted })
    }

    @Test
    fun `a RAW stays on the card while its companion failed`() = runBlocking<Unit> {
        val companion = item("A.JPG", group = "A", role = ImportItemRole.COMPANION)
        val raw = item("A.ARW")
        val other = item("B.ARW")
        val jobId = job(listOf(raw, companion, other), deleteSource = true)
        io.corruptWrites["A.JPG"] = 2

        run(jobId)

        assertEquals(setOf("A.ARW", "A.JPG"), io.files(cardDir).keys)
        assertFalse(items(jobId).getValue("A.ARW").sourceDeleted)
        assertTrue(items(jobId).getValue("B.ARW").sourceDeleted)
    }

    @Test
    fun `nothing is deleted when the setting is off`() = runBlocking<Unit> {
        run(job(listOf(item("A.ARW"))))
        assertEquals(setOf("A.ARW"), io.files(cardDir).keys)
    }

    @Test
    fun `a stopped job deletes nothing`() = runBlocking<Unit> {
        val second = item("B.ARW")
        val jobId = job(listOf(item("A.ARW"), second), deleteSource = true)
        io.onSourceRead = { name ->
            if (name == "B.ARW") {
                io.sourceAvailable = false
                io.unreadable += second.sourceUri
            }
        }

        run(jobId)

        assertEquals(setOf("A.ARW", "B.ARW"), io.files(cardDir).keys)
        assertFalse(items(jobId).values.any { it.sourceDeleted })
    }

    @Test
    fun `a duplicate skipped by the planner is never deleted from the card`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW", state = ImportItemState.SKIPPED_DUPLICATE), item("B.ARW")), deleteSource = true)

        run(jobId)

        assertEquals(setOf("A.ARW"), io.files(cardDir).keys)
    }

    @Test
    fun `a failed delete is listed and isn't fatal`() = runBlocking<Unit> {
        val raw = item("A.ARW")
        val jobId = job(listOf(raw), deleteSource = true)
        io.undeletable += raw.sourceUri

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        val done = items(jobId).getValue("A.ARW")
        assertFalse(done.sourceDeleted)
        assertEquals("Couldn't delete it from the card", done.error)
    }

    @Test
    fun `retrying after a failure copies only what failed`() = runBlocking<Unit> {
        val jobId = job(listOf(item("BAD.ARW"), item("GOOD.ARW")))
        io.corruptWrites["BAD.ARW"] = 2
        run(jobId)

        database.importJobDao().requeueFailed(jobId)
        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(2, job.doneFiles)
        assertEquals(0, job.failedFiles)
        assertEquals(2, library().size)
    }

    @Test
    fun `a job with no location pauses rather than crashes`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        database.locationDao().delete(database.locationDao().getById(locationId)!!)

        assertEquals(ImportJobStatus.PAUSED, run(jobId).status)
    }

    @Test
    fun `a cancelled job doesn't run again`() = runBlocking<Unit> {
        val jobId = job(listOf(item("A.ARW")))
        database.importJobDao().setStatus(jobId, ImportJobStatus.CANCELLED)

        run(jobId)

        assertTrue(library().isEmpty())
        assertTrue(finished.isEmpty())
    }

    // --- Add on import (R7.4, D8) ---

    private suspend fun newCollection(): Long =
        (collections.createCollection(PhotoCollection.locationKey(database.locationDao().getById(locationId)!!), "Shoot") as CollectionChange.Done).id

    /** The file names in the collection, in its order. */
    private suspend fun filed(collectionId: Long): List<String> {
        val names = database.rawPhotoDao().getByRoot(tree).associate { it.id to it.fileName }
        return collections.memberIds(collectionId).map { names.getValue(it) }
    }

    @Test
    fun `groups are filed in capture order`() = runBlocking<Unit> {
        val id = newCollection()
        val jobId = job(
            listOf(
                item("LATE.ARW").copy(captureDate = 3_000L),
                item("FIRST.ARW").copy(captureDate = 1_000L),
                item("MIDDLE.ARW").copy(captureDate = 2_000L),
            ),
            collectionId = id,
        )

        run(jobId)

        assertEquals(listOf("FIRST.ARW", "MIDDLE.ARW", "LATE.ARW"), filed(id))
    }

    @Test
    fun `a failed group is never filed, and neither is one with a failed companion`() = runBlocking<Unit> {
        val id = newCollection()
        val jobId = job(
            listOf(
                item("BAD.ARW").copy(captureDate = 1_000L),
                item("PAIR.ARW").copy(captureDate = 2_000L),
                item("PAIR.JPG", group = "PAIR", role = ImportItemRole.COMPANION).copy(captureDate = 2_000L),
                item("GOOD.ARW").copy(captureDate = 3_000L),
            ),
            collectionId = id,
        )
        io.corruptWrites["BAD.ARW"] = 2
        io.corruptWrites["PAIR.JPG"] = 2

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED_WITH_ERRORS, job.status)
        assertEquals(listOf("GOOD.ARW"), filed(id))
    }

    @Test
    fun `retrying a failed group files it when it lands`() = runBlocking<Unit> {
        val id = newCollection()
        val jobId = job(listOf(item("A.ARW"), item("B.ARW")), collectionId = id)
        io.corruptWrites["A.ARW"] = 2
        run(jobId)
        assertEquals(listOf("B.ARW"), filed(id))

        io.corruptWrites.clear()
        database.importJobDao().requeueFailed(jobId)
        database.importJobDao().setStatus(jobId, ImportJobStatus.QUEUED)
        run(jobId)

        assertEquals(setOf("A.ARW", "B.ARW"), filed(id).toSet())
        assertEquals(2, filed(id).size)
    }

    @Test
    fun `running a finished job again does not file anything twice`() = runBlocking<Unit> {
        val id = newCollection()
        val jobId = job(listOf(item("A.ARW").copy(captureDate = 1L), item("B.ARW").copy(captureDate = 2L)), collectionId = id)
        run(jobId)
        val before = collections.memberIds(id)

        database.importJobDao().setStatus(jobId, ImportJobStatus.QUEUED)
        run(jobId)

        assertEquals(before, collections.memberIds(id))
        assertEquals(2, before.size)
    }

    @Test
    fun `a kill after the files landed still files the group on resume`() = runBlocking<Unit> {
        val id = newCollection()
        val dir = io.addDir(io.addDir(io.root(tree), "2026"), "2026-11-06")
        val finished = io.addFile(dir, "A.ARW", bytes(20))
        val done = item("A.ARW", state = ImportItemState.DONE).copy(targetUri = finished, sizeBytes = 20L)
        val jobId = job(listOf(done), collectionId = id)

        run(jobId)

        assertEquals(listOf("A.ARW"), filed(id))
    }

    @Test
    fun `duplicates skipped by the planner are not filed and a job without a collection files nothing`() = runBlocking<Unit> {
        val id = newCollection()
        val skippedJob = job(listOf(item("DUP.ARW", state = ImportItemState.SKIPPED_DUPLICATE)), collectionId = id)
        run(skippedJob)
        assertEquals(emptyList<String>(), collections.memberIds(id))

        val plainJob = job(listOf(item("PLAIN.ARW")))
        run(plainJob)
        assertEquals(emptyList<String>(), collections.memberIds(id))
    }

    @Test
    fun `a collection deleted during the import does not stop it`() = runBlocking<Unit> {
        val id = newCollection()
        val jobId = job(listOf(item("A.ARW")), collectionId = id)
        collections.delete(id)

        val job = run(jobId)

        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(1, database.rawPhotoDao().getByRoot(tree).size)
    }
}
