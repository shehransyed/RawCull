package dev.shehran.rawcull.data.preview

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.importer.CandidateFile
import dev.shehran.rawcull.importer.ImportCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThumbnailStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: PreviewCacheDatabase
    private lateinit var dao: PreviewCacheDao
    private lateinit var root: File
    private lateinit var store: ThumbnailStore
    private var limit = 1000L
    private var now = 1_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PreviewCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao()
        root = folder.newFolder("previews")
        store = ThumbnailStore(dao, root, { limit }, { now }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = database.close()

    private fun key(name: String) = name.padStart(64, 'a')

    private suspend fun put(name: String, bytes: Int = 100) {
        store.write(key(name), ByteArray(bytes) { 1 }, 512, 341)
        now += 100_000
    }

    @Test
    fun `written bytes read back, under the two-hex folder`() = runBlocking {
        val jpeg = byteArrayOf(1, 2, 3)
        store.write(key("b"), jpeg, 512, 341)
        val read = store.read(key("b"))!!
        assertArrayEquals(jpeg, read.jpeg)
        assertEquals(512 to 341, read.width to read.height)
        assertTrue(File(root, "aa/${key("b")}.jpg").exists())
    }

    @Test
    fun `removing forgets the file, the row and the scan`() = runBlocking {
        put("1")
        put("2")
        dao.putScan(ScanRow(key("1"), "[]", 1, now))
        store.remove(listOf(key("1")))
        assertNull(store.read(key("1")))
        assertNull(dao.scan(key("1")))
        assertTrue(store.contains(key("2")))
        assertEquals(1, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `nothing temporary stays behind`() = runBlocking {
        put("b")
        assertEquals(1, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `eviction removes the least recently used first`() = runBlocking {
        put("1", 400)
        put("2", 400)
        put("3", 400) // 1200 bytes against a limit of 1000
        store.read(key("1")) // Now the oldest is 2

        store.evict()

        assertNotNull(store.read(key("1")))
        assertNull(store.read(key("2")))
        assertNotNull(store.read(key("3")))
        assertEquals(800L, store.usedBytes())
        assertEquals(2, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `eviction takes the scan with the thumbnail`() = runBlocking {
        put("1", 900)
        dao.putScan(ScanRow(key("1"), "[]", 1, now))
        put("2", 900)
        store.evict()
        assertNull(dao.scan(key("1")))
    }

    @Test
    fun `a lower limit takes effect on the next eviction`() = runBlocking {
        repeat(5) { put("$it", 100) }
        limit = 250
        store.evict()
        assertTrue(store.usedBytes() <= 250)
        limit = 10_000
        put("9", 100)
        store.evict()
        assertEquals(300L, store.usedBytes())
    }

    @Test
    fun `a limit of nothing empties the store without looping`() = runBlocking {
        put("1")
        limit = 0
        store.evict()
        assertEquals(0L, store.usedBytes())
    }

    @Test
    fun `writing keeps the store near its limit on its own`() = runBlocking {
        limit = 500
        repeat(40) { put("$it", 100) }
        assertTrue(store.usedBytes() <= 500 + 16 * 100)
    }

    @Test
    fun `a file deleted behind the index is a miss, and the row goes`() = runBlocking {
        put("b")
        root.deleteRecursively()
        assertNull(store.read(key("b")))
        assertNull(dao.thumbnail(key("b")))
    }

    @Test
    fun `reconcile drops rows without files and files without rows`() = runBlocking {
        put("1")
        put("2")
        File(root, "aa/${key("1")}.jpg").delete()
        val stray = File(root, "aa/left-over.tmp").apply { writeBytes(ByteArray(5)) }

        store.reconcile()

        assertNull(dao.thumbnail(key("1")))
        assertNotNull(dao.thumbnail(key("2")))
        assertFalse(stray.exists())
        assertEquals(1, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `concurrent writes of one key leave one good file`() = runBlocking {
        val writers = (1..16).map { i ->
            async(Dispatchers.IO) { store.write(key("b"), ByteArray(50) { i.toByte() }, 512, 341) }
        }
        writers.awaitAll()

        val read = store.read(key("b"))!!
        assertEquals(50, read.jpeg.size)
        assertEquals(1, read.jpeg.distinct().size)
        assertEquals(1, root.walkTopDown().count { it.isFile })
        assertEquals(50L, store.usedBytes())
    }

    @Test
    fun `clear empties index, scans and folder`() = runBlocking {
        put("1")
        dao.putScan(ScanRow(key("1"), "[]", 1, now))
        store.clear()
        assertEquals(0L, store.usedBytes())
        assertNull(dao.scan(key("1")))
        assertEquals(0, root.walkTopDown().count { it.isFile })
        put("2") // Still usable afterwards
        assertNotNull(store.read(key("2")))
    }

    @Test
    fun `keys change with the file and the companion`() {
        val photo = PhotoAsset(
            id = "1",
            rootUri = "root",
            fileName = "a.arw",
            fileUri = "content://a",
            format = PhotoFormat.ARW,
            fileSizeBytes = 100,
            fileLastModified = 5,
            dateTaken = 0,
        )
        val base = PreviewKeys.source(photo).key
        assertEquals(64, base.length)
        assertEquals(base, PreviewKeys.source(photo.copy(rating = 3)).key)
        assertNotEquals(base, PreviewKeys.source(photo.copy(fileLastModified = 6)).key)
        assertNotEquals(base, PreviewKeys.source(photo.copy(fileSizeBytes = 101)).key)
        assertNotEquals(base, PreviewKeys.source(photo.copy(companionFileUri = "content://b", companionFileSizeBytes = 9)).key)
    }

    @Test
    fun `a card entry and its throwaway row agree`() {
        val candidate = ImportCandidate(
            id = "1",
            stemGroup = "g",
            stem = "a",
            files = listOf(
                CandidateFile("content://a", "a.arw", 100, ImportItemRole.PRIMARY, PhotoFormat.ARW),
                CandidateFile("content://b", "a.jpg", 50, ImportItemRole.COMPANION, PhotoFormat.JPEG),
            ),
            fallbackDate = 7,
        )
        val source = PreviewKeys.source(candidate)
        assertEquals("content://b", source.companionUri)
        assertEquals(PhotoFormat.JPEG, source.companionFormat)
        assertEquals(PreviewKeys.fileKey("content://a", 100, 7, "content://b", 50), source.key)
    }
}
