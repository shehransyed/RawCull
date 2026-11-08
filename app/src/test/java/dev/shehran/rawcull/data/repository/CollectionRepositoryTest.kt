package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.xmp.LocalSidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XMP_TOOLKIT
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CollectionRepository
    private var now = 100L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CollectionRepository(database, clock = { now++ })
    }

    @After
    fun tearDown() = database.close()

    private fun photo(id: String) = PhotoAsset(
        id = id,
        rootUri = "root",
        fileName = "$id.ARW",
        fileUri = "content://$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 1L,
    )

    private fun CollectionChange.id() = (this as CollectionChange.Done).id

    @Test
    fun `names must be non-blank and unique among siblings ignoring case`() = runTest {
        val set = repository.createSet(LOC, "Weddings").id()
        repository.createCollection(LOC, "Smith", parentId = set).id()

        assertTrue(repository.createCollection(LOC, "  ") is CollectionChange.Refused)
        assertTrue(repository.createCollection(LOC, "weddings") is CollectionChange.Refused)
        assertTrue(repository.createSet(LOC, "SMITH ", parentId = set) is CollectionChange.Refused)
        // Same name elsewhere is fine: at the top level, or in another Location.
        repository.createCollection(LOC, "Smith").id()
        repository.createCollection(OTHER_LOC, "Weddings").id()
        assertEquals("Trimmed", repository.get(repository.createCollection(LOC, " Trimmed ").id())!!.name)
    }

    @Test
    fun `only sets hold collections and only collections hold photos`() = runTest {
        val collection = repository.createCollection(LOC, "Picks").id()
        val set = repository.createSet(LOC, "Archive").id()

        assertTrue(repository.createCollection(LOC, "Inner", parentId = collection) is CollectionChange.Refused)
        assertTrue(repository.add(set, listOf("a")).isEmpty())
        assertTrue(repository.memberIds(set).isEmpty())
    }

    @Test
    fun `rename refuses a clash but allows a change of case`() = runTest {
        val a = repository.createCollection(LOC, "Alpha").id()
        repository.createCollection(LOC, "Beta").id()

        assertTrue(repository.rename(a, "beta") is CollectionChange.Refused)
        assertEquals(CollectionChange.Done(a), repository.rename(a, "ALPHA"))
        assertEquals("ALPHA", repository.get(a)!!.name)
    }

    @Test
    fun `move refuses cycles and accepts the top level`() = runTest {
        val outer = repository.createSet(LOC, "Outer").id()
        val inner = repository.createSet(LOC, "Inner", parentId = outer).id()
        val leaf = repository.createCollection(LOC, "Leaf", parentId = inner).id()

        assertTrue(repository.move(outer, inner) is CollectionChange.Refused)
        assertTrue(repository.move(outer, outer) is CollectionChange.Refused)
        assertTrue(repository.move(inner, leaf) is CollectionChange.Refused)
        assertEquals(CollectionChange.Done(leaf), repository.move(leaf, null))
        assertNull(repository.get(leaf)!!.parentId)
        assertEquals(CollectionChange.Done(leaf), repository.move(leaf, outer))
        assertEquals(outer, repository.get(leaf)!!.parentId)
    }

    @Test
    fun `adding appends new members in order and reports only those`() = runTest {
        val c = repository.createCollection(LOC, "Picks").id()

        assertEquals(listOf("b", "a"), repository.add(c, listOf("b", "a", "b")))
        assertEquals(listOf("c"), repository.add(c, listOf("a", "c")))

        assertEquals(listOf("b", "a", "c"), repository.memberIds(c))
    }

    @Test
    fun `remove and restore put photos back in their places`() = runTest {
        val c = repository.createCollection(LOC, "Picks").id()
        repository.add(c, listOf("a", "b", "c", "d"))

        val removed = repository.remove(c, listOf("b", "d", "zz"))
        assertEquals(setOf("b", "d"), removed.map { it.assetId }.toSet())
        assertEquals(listOf("a", "c"), repository.memberIds(c))

        repository.restore(removed)
        assertEquals(listOf("a", "b", "c", "d"), repository.memberIds(c))
    }

    @Test
    fun `reorder rewrites the whole order and keeps every id once`() = runTest {
        val c = repository.createCollection(LOC, "Picks").id()
        repository.add(c, listOf("a", "b", "c", "d"))

        repository.reorder(c, listOf("d", "x", "b", "d"))

        val order = repository.memberIds(c)
        assertEquals(listOf("d", "b", "a", "c"), order)
        assertEquals(order.size, order.toSet().size)
        assertEquals(listOf("e"), repository.add(c, listOf("e")))
        assertEquals(listOf("d", "b", "a", "c", "e"), repository.memberIds(c))
    }

    @Test
    fun `members skip ids without a row but keep their place`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(photo("a"), photo("c")))
        val c = repository.createCollection(LOC, "Picks").id()
        repository.add(c, listOf("c", "missing", "a"))

        assertEquals(listOf("c", "a"), repository.members(c).map { it.id })
        assertEquals(listOf("c", "missing", "a"), repository.memberIds(c))
    }

    @Test
    fun `a photo can be in many collections`() = runTest {
        val one = repository.createCollection(LOC, "One").id()
        val two = repository.createCollection(LOC, "two").id()
        repository.createCollection(LOC, "Three").id()
        repository.add(one, listOf("a"))
        repository.add(two, listOf("a", "b"))

        assertEquals(listOf("One", "two"), repository.containing("a").map { it.name })
        assertEquals(listOf("One", "two"), repository.observeContaining("a").first().map { it.name })
    }

    @Test
    fun `deleting a set deletes everything inside it and leaves photos alone`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(photo("a")))
        val set = repository.createSet(LOC, "Weddings").id()
        val inner = repository.createSet(LOC, "Smith", parentId = set).id()
        val c1 = repository.createCollection(LOC, "Ceremony", parentId = inner).id()
        val c2 = repository.createCollection(LOC, "Jones", parentId = set).id()
        val keep = repository.createCollection(LOC, "Keep").id()
        repository.add(c1, listOf("a"))
        repository.add(c2, listOf("a", "b"))
        repository.add(keep, listOf("a"))

        repository.delete(set)

        assertEquals(listOf("Keep"), repository.tree(LOC).rows().map { it.collection.name })
        listOf(set, inner, c1, c2).forEach { assertNull(repository.get(it)) }
        assertTrue(repository.memberIds(c1).isEmpty())
        assertTrue(repository.memberIds(c2).isEmpty())
        assertEquals(listOf("a"), repository.memberIds(keep))
        assertEquals(1, database.rawPhotoDao().getByIds(listOf("a")).size)
    }

    @Test
    fun `the foreign keys cascade on their own too`() = runTest {
        val set = repository.createSet(LOC, "Weddings").id()
        val c = repository.createCollection(LOC, "Smith", parentId = set).id()
        repository.add(c, listOf("a"))

        database.collectionDao().deleteCollections(listOf(set))

        assertNull(repository.get(c))
        assertTrue(repository.memberIds(c).isEmpty())
    }

    @Test
    fun `deleting photos from the library removes their memberships`() = runTest {
        val catalog = database.rawPhotoDao()
        catalog.upsertAll(listOf(photo("a"), photo("b")))
        val c = repository.createCollection(LOC, "Picks").id()
        repository.add(c, listOf("a", "b"))

        // The reject-deletion path ends in PhotoCatalog.removeRows.
        val store = LocalSidecarStore()
        val queue = XmpWriteQueue(CoroutineScope(Job()), catalog, SidecarStores(store, store), { XmpPrefs() }, XMP_TOOLKIT)
        RawCatalogRepository(ApplicationProvider.getApplicationContext(), database, catalog, queue).removeRows(listOf("a"))

        assertEquals(listOf("b"), repository.memberIds(c))
    }

    private companion object {
        const val LOC = 7L
        const val OTHER_LOC = 8L
    }
}
