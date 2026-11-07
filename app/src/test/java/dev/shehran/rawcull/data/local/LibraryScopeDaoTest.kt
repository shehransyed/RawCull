package dev.shehran.rawcull.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryScopeDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RawPhotoDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.rawPhotoDao()
    }

    @After
    fun tearDown() = database.close()

    private fun row(id: String, dir: String, root: String = "root", jobId: Long? = null, status: XmpSyncStatus = XmpSyncStatus.SYNCED) =
        PhotoAsset(
            id = id,
            rootUri = root,
            relativeDir = dir,
            fileName = "$id.ARW",
            fileUri = "content://$id",
            format = PhotoFormat.ARW,
            fileSizeBytes = 1L,
            dateTaken = id.hashCode().toLong(),
            lastImportJobId = jobId,
            xmpSyncStatus = status,
        )

    private suspend fun scope(folder: String, root: String = "root") =
        dao.observeScope(root, folder).first().map { it.id }.toSet()

    @Test
    fun `an empty folder is the whole location`() = runTest {
        dao.upsertAll(listOf(row("a", ""), row("b", "2026"), row("other", "2026", root = "elsewhere")))
        assertEquals(setOf("a", "b"), scope(""))
    }

    @Test
    fun `a folder includes its subfolders`() = runTest {
        dao.upsertAll(listOf(row("a", "2026"), row("b", "2026/2026-11-06"), row("c", "2026/2026-11-06/raw")))
        assertEquals(setOf("a", "b", "c"), scope("2026"))
        assertEquals(setOf("b", "c"), scope("2026/2026-11-06"))
    }

    @Test
    fun `a folder doesn't include siblings that start the same way`() = runTest {
        dao.upsertAll(listOf(row("a", "2026"), row("b", "2026-old"), row("c", "20260")))
        assertEquals(setOf("a"), scope("2026"))
    }

    @Test
    fun `underscore in a folder name is literal`() = runTest {
        dao.upsertAll(listOf(row("a", "a_b/x"), row("b", "aXb/x"), row("c", "a_b")))
        assertEquals(setOf("a", "c"), scope("a_b"))
    }

    @Test
    fun `percent in a folder name is literal`() = runTest {
        dao.upsertAll(listOf(row("a", "50%/x"), row("b", "50 percent/x")))
        assertEquals(setOf("a"), scope("50%"))
    }

    @Test
    fun `backslash in a folder name is literal`() = runTest {
        dao.upsertAll(listOf(row("a", """a\b/x"""), row("b", "ab/x")))
        assertEquals(setOf("a"), scope("""a\b"""))
    }

    @Test
    fun `getScope matches observeScope`() = runTest {
        dao.upsertAll(listOf(row("a", "2026"), row("b", "2026/x"), row("c", "2027")))
        assertEquals(setOf("a", "b"), dao.getScope("root", "2026").map { it.id }.toSet())
        assertEquals(3, dao.getScope("root", "").size)
    }

    @Test
    fun `folder counts are per folder and per location`() = runTest {
        dao.upsertAll(listOf(row("a", "x"), row("b", "x"), row("c", "y"), row("d", "x", root = "elsewhere")))
        assertEquals(mapOf("x" to 2, "y" to 1), dao.observeFolderCounts("root").first())
    }

    @Test
    fun `rows of an import job can be observed`() = runTest {
        dao.upsertAll(listOf(row("a", "x", jobId = 1), row("b", "x", jobId = 2), row("c", "x")))
        assertEquals(listOf("a"), dao.observeByImportJob(1).first().map { it.id })
    }

    @Test
    fun `unsaved rows are the pending and writing ones of that location`() = runTest {
        dao.upsertAll(
            listOf(
                row("a", "x", status = XmpSyncStatus.PENDING),
                row("b", "x", status = XmpSyncStatus.WRITING),
                row("c", "x", status = XmpSyncStatus.ERROR),
                row("d", "x", status = XmpSyncStatus.SYNCED),
                row("e", "x", root = "elsewhere", status = XmpSyncStatus.PENDING),
            ),
        )
        assertEquals(2, dao.countUnsaved("root"))
    }

    @Test
    fun `deleting a root leaves other roots alone`() = runTest {
        dao.upsertAll(listOf(row("a", "x"), row("b", "x", root = "elsewhere")))
        dao.deleteByRoot("root")
        assertEquals(listOf("b"), dao.getIdsByRoot("elsewhere"))
        assertEquals(emptyList<String>(), dao.getIdsByRoot("root"))
    }
}
