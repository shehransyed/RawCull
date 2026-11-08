package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationRepositoryTest {
    private class FakeAccess : LocationAccess {
        var grantFails = false
        val available = HashSet<String>()
        val taken = ArrayList<String>()
        val released = ArrayList<String>()

        override fun takePersistable(treeUri: String): Boolean {
            if (!grantFails) taken += treeUri
            return !grantFails
        }

        override fun release(treeUri: String) {
            released += treeUri
        }

        override fun isAvailable(treeUri: String) = treeUri in available
        override fun displayName(treeUri: String) = treeUri.substringAfterLast('/')
    }

    private lateinit var database: AppDatabase
    private lateinit var repository: LocationRepository
    private val access = FakeAccess()
    private var flushes = 0
    private var flushAction: suspend () -> Unit = {}
    private var grantInUse = false

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = LocationRepository(
            locations = database.locationDao(),
            photos = database.rawPhotoDao(),
            collections = CollectionRepository(database),
            access = access,
            flushXmp = {
                flushes++
                flushAction()
            },
            isGrantInUse = { grantInUse },
            clock = { 1000L },
        )
    }

    @After
    fun tearDown() = database.close()

    private fun row(id: String, root: String, status: XmpSyncStatus = XmpSyncStatus.SYNCED) = PhotoAsset(
        id = id,
        rootUri = root,
        fileName = "$id.ARW",
        fileUri = "content://$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 1L,
        xmpSyncStatus = status,
    )

    @Test
    fun `adding persists the grant and names the location after the folder`() = runTest {
        val location = repository.add("content://tree/Photos")

        assertEquals("Photos", location?.displayName)
        assertEquals(1000L, location?.addedAt)
        assertEquals(listOf("content://tree/Photos"), access.taken)
    }

    @Test
    fun `adding the same tree twice returns the existing location`() = runTest {
        val first = repository.add("content://tree/Photos")
        val second = repository.add("content://tree/Photos")

        assertEquals(first?.id, second?.id)
        assertEquals(1, repository.getAll().size)
    }

    @Test
    fun `no location is added without a grant`() = runTest {
        access.grantFails = true
        assertNull(repository.add("content://tree/Photos"))
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun `blank names are ignored`() = runTest {
        val id = repository.add("content://tree/Photos")!!.id
        repository.rename(id, "   ")
        assertEquals("Photos", repository.get(id)?.displayName)

        repository.rename(id, " Archive ")
        assertEquals("Archive", repository.get(id)?.displayName)
    }

    @Test
    fun `relink swaps the tree, drops the old rows and releases the old grant`() = runTest {
        val id = repository.add("content://tree/Old")!!.id
        database.rawPhotoDao().upsertAll(listOf(row("a", "content://tree/Old")))

        val result = repository.relink(id, "content://tree/New")

        assertEquals(LocationChange.Done, result)
        assertEquals("content://tree/New", repository.get(id)?.treeUri)
        assertTrue(database.rawPhotoDao().getIdsByRoot("content://tree/Old").isEmpty())
        assertEquals(listOf("content://tree/Old"), access.released)
    }

    @Test
    fun `relink flushes first and refuses while edits are unsaved`() = runTest {
        val id = repository.add("content://tree/Old")!!.id
        database.rawPhotoDao().upsertAll(listOf(row("a", "content://tree/Old", XmpSyncStatus.PENDING)))

        val result = repository.relink(id, "content://tree/New")

        assertTrue(result is LocationChange.Refused)
        assertEquals(1, flushes)
        assertEquals("content://tree/Old", repository.get(id)?.treeUri)
        assertEquals(listOf("a"), database.rawPhotoDao().getIdsByRoot("content://tree/Old"))
        assertTrue(access.released.isEmpty())
    }

    @Test
    fun `a flush that clears the queue lets the change through`() = runTest {
        val id = repository.add("content://tree/Old")!!.id
        database.rawPhotoDao().upsertAll(listOf(row("a", "content://tree/Old", XmpSyncStatus.PENDING)))
        flushAction = { database.rawPhotoDao().upsertAll(listOf(row("a", "content://tree/Old"))) }

        assertEquals(LocationChange.Done, repository.remove(id))
    }

    @Test
    fun `relink to another location's tree is refused`() = runTest {
        val id = repository.add("content://tree/A")!!.id
        repository.add("content://tree/B")

        assertTrue(repository.relink(id, "content://tree/B") is LocationChange.Refused)
        assertEquals("content://tree/A", repository.get(id)?.treeUri)
    }

    @Test
    fun `remove deletes rows and the grant`() = runTest {
        val id = repository.add("content://tree/A")!!.id
        database.rawPhotoDao().upsertAll(listOf(row("a", "content://tree/A")))

        assertEquals(LocationChange.Done, repository.remove(id))

        assertNull(repository.get(id))
        assertTrue(database.rawPhotoDao().getIdsByRoot("content://tree/A").isEmpty())
        assertEquals(listOf("content://tree/A"), access.released)
    }

    @Test
    fun `remove deletes the location's collections and leaves other locations' alone`() = runTest {
        val collections = CollectionRepository(database)
        val a = repository.add("content://tree/A")!!.id
        val b = repository.add("content://tree/B")!!.id
        val set = (collections.createSet(a, "Weddings") as CollectionChange.Done).id
        val inSet = (collections.createCollection(a, "Smith", parentId = set) as CollectionChange.Done).id
        val other = (collections.createCollection(b, "Keep") as CollectionChange.Done).id
        collections.add(inSet, listOf("a1", "a2"))
        collections.add(other, listOf("b1"))

        assertEquals(LocationChange.Done, repository.remove(a))

        assertTrue(collections.tree(a).byId.isEmpty())
        assertTrue(collections.memberIds(inSet).isEmpty())
        assertEquals(listOf("Keep"), collections.tree(b).rows().map { it.collection.name })
        assertEquals(listOf("b1"), collections.memberIds(other))
    }

    @Test
    fun `removing the demo library removes the demo's collections`() = runTest {
        val collections = CollectionRepository(database)
        val demo = repository.demo()
        val key = PhotoCollection.locationKey(demo)
        collections.createCollection(key, "Picks")

        repository.remove(demo.id)

        assertTrue(collections.tree(key).byId.isEmpty())
    }

    @Test
    fun `remove refuses while edits are unsaved`() = runTest {
        val id = repository.add("content://tree/A")!!.id
        database.rawPhotoDao().upsertAll(listOf(row("a", "content://tree/A", XmpSyncStatus.WRITING)))

        assertTrue(repository.remove(id) is LocationChange.Refused)
        assertNotNull(repository.get(id))
    }

    @Test
    fun `a grant a card still uses is kept`() = runTest {
        grantInUse = true
        val id = repository.add("content://tree/A")!!.id

        repository.remove(id)

        assertTrue(access.released.isEmpty())
    }

    @Test
    fun `availability comes from the volume`() = runTest {
        val location = repository.add("content://tree/A")!!
        assertFalse(repository.isAvailable(location))

        access.available += "content://tree/A"
        assertTrue(repository.isAvailable(location))
    }

    @Test
    fun `the demo library needs no grant, is always available and is only created once`() = runTest {
        val demo = repository.demo()

        assertEquals(DEMO_ROOT_URI, demo.treeUri)
        assertEquals("Demo library", demo.displayName)
        assertTrue(repository.isAvailable(demo))
        assertEquals(demo.id, repository.demo().id)
        assertTrue(access.taken.isEmpty())

        repository.remove(demo.id)
        assertTrue(access.released.isEmpty())
    }
}
