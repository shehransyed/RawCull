package dev.shehran.rawcull.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.ImportedFile
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.data.model.VerifyMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportDaosTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).addCallback(AppDatabase.SeedOnCreate).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun location(uri: String = "content://tree/a", name: String = "Photos") =
        LibraryLocation(treeUri = uri, displayName = name, addedAt = 1L, lastOpenedAt = 1L)

    private fun job(locationId: Long = 1L, status: ImportJobStatus = ImportJobStatus.QUEUED, doneFiles: Int = 0) = ImportJob(
        createdAt = 10L,
        status = status,
        sourceTreeUri = "content://card",
        sourceLabel = "SD card",
        locationId = locationId,
        template = "2026/2026-11-06",
        verifyMode = VerifyMode.FULL,
        doneFiles = doneFiles,
    )

    private fun item(name: String, captureDate: Long = 0L) = ImportJobItem(
        jobId = 0,
        groupKey = name.substringBeforeLast('.'),
        sourceUri = "content://card/$name",
        sourceName = name,
        sizeBytes = 100L,
        targetRelativeDir = "2026",
        targetName = name,
        role = ImportItemRole.PRIMARY,
        captureDate = captureDate,
    )

    @Test
    fun `a fresh database has the Standard preset`() = runTest {
        val presets = database.importPresetDao().observeAll().first()
        assertEquals(listOf(ImportPreset.STANDARD_NAME), presets.map { it.name })
        assertEquals(StructureMode.DATE, presets.single().structureMode)
        assertEquals("{year}/{year}-{month}-{day}", presets.single().datePattern)
    }

    @Test
    fun `a tree can only be added once`() = runTest {
        val dao = database.locationDao()
        val first = dao.insert(location())
        assertTrue(first > 0)
        assertEquals(-1L, dao.insert(location(name = "Again")))
        assertEquals(listOf("Photos"), dao.getAll().map { it.displayName })
    }

    @Test
    fun `locations sort by name and can be renamed and relinked`() = runTest {
        val dao = database.locationDao()
        val b = dao.insert(location("content://b", "beta"))
        dao.insert(location("content://a", "Alpha"))
        assertEquals(listOf("Alpha", "beta"), dao.observeAll().first().map { it.displayName })

        dao.rename(b, "Gamma")
        dao.updateTreeUri(b, "content://c")
        val updated = dao.getById(b)
        assertEquals("Gamma", updated?.displayName)
        assertEquals("content://c", updated?.treeUri)
        assertEquals(b, dao.getByTreeUri("content://c")?.id)
    }

    @Test
    fun `removing a location clears it from presets instead of deleting them`() = runTest {
        val locations = database.locationDao()
        val presets = database.importPresetDao()
        val locationId = locations.insert(location())
        val presetId = presets.insert(ImportPreset(name = "Weddings", locationId = locationId))

        locations.delete(locations.getById(locationId)!!)

        val preset = presets.getById(presetId)
        assertNotNull(preset)
        assertNull(preset?.locationId)
    }

    @Test
    fun `the duplicate index matches on hash and size together`() = runTest {
        val dao = database.importedFileDao()
        dao.insert(ImportedFile(headHash = "abc", sizeBytes = 10, originalName = "a", targetUri = "t", importedAt = 1))
        dao.insert(ImportedFile(headHash = "abd", sizeBytes = 10, originalName = "b", targetUri = "t", importedAt = 1))

        assertTrue(dao.contains("abc", 10))
        assertFalse(dao.contains("abc", 11))
        assertFalse(dao.contains("xyz", 10))
        assertEquals(listOf(10L), dao.knownSizes())
    }

    @Test
    fun `a job and its items are written together and ordered by capture date`() = runTest {
        val dao = database.importJobDao()
        val jobId = dao.insertJobWithItems(job(), listOf(item("late.ARW", 20), item("early.ARW", 10)))

        assertEquals(listOf("early.ARW", "late.ARW"), dao.getItems(jobId).map { it.sourceName })
        assertTrue(dao.getItems(jobId).all { it.jobId == jobId })
    }

    @Test
    fun `items need an existing job, and go with it`() = runTest {
        val dao = database.importJobDao()
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { dao.insertItems(listOf(item("orphan.ARW").copy(jobId = 99))) }
        }

        val jobId = dao.insertJobWithItems(job(), listOf(item("a.ARW")))
        database.openHelper.writableDatabase.execSQL("DELETE FROM import_jobs WHERE id = $jobId")
        assertTrue(dao.getItems(jobId).isEmpty())
    }

    @Test
    fun `only queued and running jobs are active`() = runTest {
        val dao = database.importJobDao()
        dao.insertJob(job(status = ImportJobStatus.COMPLETED))
        assertNull(dao.getActiveJob())

        val running = dao.insertJob(job(status = ImportJobStatus.RUNNING))
        assertEquals(running, dao.getActiveJob()?.id)
        assertEquals(running, dao.observeActiveJob().first()?.id)

        dao.setStatus(running, ImportJobStatus.PAUSED)
        assertNull(dao.getActiveJob())
    }

    @Test
    fun `latest job is the newest one that imported files into the location`() = runTest {
        val dao = database.importJobDao()
        val older = dao.insertJob(job(locationId = 1, doneFiles = 3))
        dao.insertJob(job(locationId = 1, doneFiles = 0))
        dao.insertJob(job(locationId = 2, doneFiles = 5))

        assertEquals(older, dao.observeLatestJob(1).first()?.id)
    }

    @Test
    fun `failed items go back to the queue without their error`() = runTest {
        val dao = database.importJobDao()
        val jobId = dao.insertJobWithItems(job(), listOf(item("a.ARW"), item("b.ARW")))
        val (a, b) = dao.getItems(jobId)
        dao.updateItem(a.copy(state = ImportItemState.FAILED, error = "Hash mismatch"))
        dao.updateItem(b.copy(state = ImportItemState.DONE))

        dao.requeueFailed(jobId)

        val items = dao.getItems(jobId).associateBy { it.sourceName }
        assertEquals(ImportItemState.QUEUED, items.getValue("a.ARW").state)
        assertNull(items.getValue("a.ARW").error)
        assertEquals(ImportItemState.DONE, items.getValue("b.ARW").state)
    }
}
