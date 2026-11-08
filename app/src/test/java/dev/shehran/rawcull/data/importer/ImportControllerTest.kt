package dev.shehran.rawcull.data.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.repository.CollectionChange
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.importer.ImportCollection
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.importer.CandidateFile
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.importer.ImportSelection
import dev.shehran.rawcull.importer.ImportSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportControllerTest {
    private class FakeScheduler : ImportScheduler {
        val enqueued = ArrayList<Long>()
        var cancels = 0

        override fun enqueue(jobId: Long) {
            enqueued += jobId
        }

        override fun cancel() {
            cancels++
        }
    }

    private val tree = "tree://library"
    private val card = ImportSource("tree://card", "SD card", "ABCD-1234")
    private val io = FakeDocumentIo()
    private val scheduler = FakeScheduler()
    private lateinit var database: AppDatabase
    private lateinit var controller: ImportController
    private lateinit var collections: CollectionRepository
    private lateinit var location: LibraryLocation
    private lateinit var cardDir: String

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        collections = CollectionRepository(database)
        val engine = ImportEngine(
            database.importJobDao(),
            database.importedFileDao(),
            database.locationDao(),
            database.rawPhotoDao(),
            io,
            collections,
            Dispatchers.Unconfined,
        )
        controller = ImportController(database.importJobDao(), engine, io, scheduler, collections, Dispatchers.Unconfined) { 7L }
        val id = database.locationDao().insert(LibraryLocation(treeUri = tree, displayName = "Photos", addedAt = 0, lastOpenedAt = 0))
        location = database.locationDao().getById(id)!!
        cardDir = io.root("tree://card")
        io.root(tree)
    }

    @After
    fun tearDown() = database.close()

    private fun candidate(name: String, content: ByteArray = ByteArray(100) { 1 }): ImportCandidate {
        val uri = io.addFile(cardDir, name, content)
        return ImportCandidate(
            id = name,
            stemGroup = name,
            stem = name.substringBeforeLast('.'),
            files = listOf(CandidateFile(uri, name, content.size.toLong(), ImportItemRole.PRIMARY, PhotoFormat.fromFileName(name))),
            captureDate = 1L,
        )
    }

    private fun request(
        candidates: List<ImportCandidate>,
        source: ImportSource = card,
        deleteFromCard: Boolean = false,
    ) = ImportRequest(
        source = source,
        location = location,
        candidates = candidates,
        selection = ImportSelection(candidates.map { it.id }.toSet()),
        template = { "2026/day" },
        verifyMode = VerifyMode.FULL,
        deleteFromCard = deleteFromCard,
    )

    private suspend fun start(request: ImportRequest) = controller.start(request) as ImportStart.Started

    @Test
    fun `starting writes the job and its items, then schedules it`() = runBlocking<Unit> {
        val started = start(request(listOf(candidate("A.ARW"))))

        val job = database.importJobDao().getJob(started.jobId)!!
        assertEquals(1, job.totalFiles)
        assertEquals(100L, job.totalBytes)
        assertEquals("2026/day", job.template)
        assertEquals(listOf("A.ARW"), database.importJobDao().getItems(started.jobId).map { it.targetName })
        assertEquals(listOf(started.jobId), scheduler.enqueued)
    }

    @Test
    fun `a second import is refused while one runs`() = runBlocking<Unit> {
        start(request(listOf(candidate("A.ARW"))))

        val second = controller.start(request(listOf(candidate("B.ARW"))))

        assertTrue(second is ImportStart.Refused)
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun `an empty selection is refused`() = runBlocking<Unit> {
        assertTrue(controller.start(request(emptyList())) is ImportStart.Refused)
    }

    @Test
    fun `the plan sees what's already in the library`() = runBlocking<Unit> {
        val content = ByteArray(100) { 1 }
        val year = io.addDir(io.root(tree), "2026")
        val day = io.addDir(year, "day")
        val existing = io.addFile(day, "A.ARW", content)
        io.addFile(day, "B.ARW", ByteArray(50))

        val started = start(request(listOf(candidate("A.ARW", content), candidate("B.ARW"))))

        val items = database.importJobDao().getItems(started.jobId).associateBy { it.sourceName }
        assertEquals(ImportItemState.SKIPPED_DUPLICATE, items.getValue("A.ARW").state)
        assertEquals(existing, items.getValue("A.ARW").targetUri)
        assertEquals("B-1.ARW", items.getValue("B.ARW").targetName)
        val job = database.importJobDao().getJob(started.jobId)!!
        assertEquals(1, job.skippedFiles)
        assertEquals(1, job.totalFiles)
    }

    @Test
    fun `only a detected card can be emptied`() = runBlocking<Unit> {
        val fromCard = start(request(listOf(candidate("A.ARW")), deleteFromCard = true))
        database.importJobDao().setStatus(fromCard.jobId, ImportJobStatus.COMPLETED)
        val fromFolder = start(
            request(listOf(candidate("B.ARW")), source = ImportSource("tree://card/folder", "Folder"), deleteFromCard = true),
        )

        assertTrue(database.importJobDao().getJob(fromCard.jobId)!!.deleteSourceAfter)
        assertFalse(database.importJobDao().getJob(fromFolder.jobId)!!.deleteSourceAfter)
    }

    @Test
    fun `resume waits for the card`() = runBlocking<Unit> {
        val started = start(request(listOf(candidate("A.ARW"))))
        database.importJobDao().setStatus(started.jobId, ImportJobStatus.SOURCE_LOST)
        io.sourceAvailable = false
        scheduler.enqueued.clear()

        assertTrue(controller.resume(started.jobId) is ImportStart.Refused)
        assertTrue(scheduler.enqueued.isEmpty())

        io.sourceAvailable = true
        assertTrue(controller.resume(started.jobId) is ImportStart.Started)
        assertEquals(listOf(started.jobId), scheduler.enqueued)
        assertEquals(ImportJobStatus.QUEUED, database.importJobDao().getJob(started.jobId)?.status)
    }

    @Test
    fun `retry requeues the failed items and schedules the same job`() = runBlocking<Unit> {
        val started = start(request(listOf(candidate("A.ARW"))))
        val dao = database.importJobDao()
        dao.setStatus(started.jobId, ImportJobStatus.COMPLETED_WITH_ERRORS)
        dao.updateItem(dao.getItems(started.jobId).single().copy(state = ImportItemState.FAILED, error = "x"))
        scheduler.enqueued.clear()

        controller.retryFailed(started.jobId)

        assertEquals(ImportItemState.QUEUED, dao.getItems(started.jobId).single().state)
        assertEquals(listOf(started.jobId), scheduler.enqueued)
    }

    @Test
    fun `cancel marks the job, stops the work and removes the partial file`() = runBlocking<Unit> {
        val started = start(request(listOf(candidate("A.ARW"))))
        val dao = database.importJobDao()
        val partial = io.addFile(io.root(tree), "A.ARW", ByteArray(3))
        dao.updateItem(dao.getItems(started.jobId).single().copy(state = ImportItemState.COPYING, targetUri = partial))

        controller.cancel(started.jobId)

        assertEquals(ImportJobStatus.CANCELLED, dao.getJob(started.jobId)?.status)
        assertEquals(1, scheduler.cancels)
        assertFalse(io.exists(partial))
        val item = dao.getItems(started.jobId).single()
        assertEquals(ImportItemState.QUEUED, item.state)
        assertNull(item.targetUri)
    }

    @Test
    fun `an active job is rescheduled on launch`() = runBlocking<Unit> {
        val started = start(request(listOf(candidate("A.ARW"))))
        scheduler.enqueued.clear()

        controller.ensureScheduled()

        assertEquals(listOf(started.jobId), scheduler.enqueued)
    }

    @Test
    fun `nothing is rescheduled without an active job`() = runBlocking<Unit> {
        controller.ensureScheduled()
        assertTrue(scheduler.enqueued.isEmpty())
    }

    // --- Add on import (R7.4, D8) ---

    private suspend fun topLevelCollections() =
        collections.tree(PhotoCollection.locationKey(location)).childrenOf(null)

    @Test
    fun `a new collection name is created once and reused by the next import`() = runBlocking<Unit> {
        val first = start(request(listOf(candidate("A.ARW"))).copy(collection = ImportCollection.New("  Trip ")))
        val created = topLevelCollections().single()
        assertEquals("Trip", created.name)
        assertEquals(created.id, database.importJobDao().getJob(first.jobId)?.collectionId)

        database.importJobDao().setStatus(first.jobId, ImportJobStatus.COMPLETED)
        val second = start(request(listOf(candidate("B.ARW"))).copy(collection = ImportCollection.New("trip")))

        assertEquals(1, topLevelCollections().size)
        assertEquals(created.id, database.importJobDao().getJob(second.jobId)?.collectionId)
    }

    @Test
    fun `an existing collection is stored on the job`() = runBlocking<Unit> {
        val id = (collections.createCollection(PhotoCollection.locationKey(location), "Keep") as CollectionChange.Done).id

        val started = start(request(listOf(candidate("A.ARW"))).copy(collection = ImportCollection.Existing(id)))

        assertEquals(id, database.importJobDao().getJob(started.jobId)?.collectionId)
    }

    @Test
    fun `a collection that is gone or is a set refuses the import before anything is queued`() = runBlocking<Unit> {
        val set = (collections.createSet(PhotoCollection.locationKey(location), "Folder") as CollectionChange.Done).id

        val missing = controller.start(request(listOf(candidate("A.ARW"))).copy(collection = ImportCollection.Existing(999)))
        val notACollection = controller.start(request(listOf(candidate("B.ARW"))).copy(collection = ImportCollection.Existing(set)))

        assertTrue(missing is ImportStart.Refused)
        assertTrue(notACollection is ImportStart.Refused)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `a new name that is a set at the top level is refused`() = runBlocking<Unit> {
        collections.createSet(PhotoCollection.locationKey(location), "Trips")

        val result = controller.start(request(listOf(candidate("A.ARW"))).copy(collection = ImportCollection.New("trips")))

        assertTrue(result is ImportStart.Refused)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `no collection chosen leaves the job without one and creates nothing`() = runBlocking<Unit> {
        val started = start(request(listOf(candidate("A.ARW"))))

        assertNull(database.importJobDao().getJob(started.jobId)?.collectionId)
        assertTrue(topLevelCollections().isEmpty())
    }

    @Test
    fun `nothing selected creates no collection`() = runBlocking<Unit> {
        val empty = request(listOf(candidate("A.ARW")))
            .copy(selection = ImportSelection(emptySet()), collection = ImportCollection.New("Ghost"))

        assertTrue(controller.start(empty) is ImportStart.Refused)
        assertTrue(topLevelCollections().isEmpty())
    }
}
