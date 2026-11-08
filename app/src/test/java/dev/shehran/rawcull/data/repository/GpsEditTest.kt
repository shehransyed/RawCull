package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.GeoPoint
import dev.shehran.rawcull.data.model.LocationFilter
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.model.location
import dev.shehran.rawcull.data.scan.CameraLocations
import dev.shehran.rawcull.data.xmp.SidecarContent
import dev.shehran.rawcull.data.xmp.SidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XMP_TOOLKIT
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.library.CullEdits
import dev.shehran.rawcull.library.LibraryFilter
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpField
import dev.shehran.rawcull.util.xmp.XmpGps
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GpsEditTest {
    private lateinit var database: AppDatabase
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val camera = ConcurrentHashMap<String, GeoPoint>()
    private val x = GeoPoint(40.0, -79.0, 10.0)
    private val y = GeoPoint(-33.5, 151.25)
    private val shot = GeoPoint(10.0, 20.0)

    private val store = object : SidecarStore {
        override fun create(asset: PhotoAsset): String = "mem://${asset.sidecarFileName}".also { files[it] = ByteArray(0) }
        override fun read(uri: String) = files[uri]?.let { SidecarContent(it, 1L) }
        override fun write(uri: String, bytes: ByteArray): Long? = 2L.also { files[uri] = bytes }
        override fun backup(uri: String) = Unit
    }

    private lateinit var queue: XmpWriteQueue
    private lateinit var repository: RawCatalogRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val cameras = CameraLocations { camera[it.id] }
        queue = XmpWriteQueue(
            CoroutineScope(Job()), database.rawPhotoDao(), SidecarStores(store, store), { XmpPrefs() }, XMP_TOOLKIT,
            cameraLocations = cameras,
        )
        repository = RawCatalogRepository(ApplicationProvider.getApplicationContext(), database, database.rawPhotoDao(), queue, cameras)
    }

    @After
    fun tearDown() = database.close()

    private fun row(id: String, at: GeoPoint? = null, inFile: Boolean = false) = PhotoAsset(
        id = id,
        rootUri = "root",
        fileName = "$id.ARW",
        fileUri = "content://$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 1L,
        gpsLatitude = at?.latitude,
        gpsLongitude = at?.longitude,
        gpsAltitude = at?.altitude,
        gpsInFile = inFile,
    )

    private suspend fun stored(id: String) = database.rawPhotoDao().getPhotoById(id)!!

    private suspend fun apply(edit: CullEdit, vararg ids: String) {
        val photos = database.rawPhotoDao().getByIds(ids.toList())
        repository.applyChange(CullEdits.resolve(edit, photos)!!)
    }

    @Test
    fun `a batch edit sets every photo and undo restores each photo's own value`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a"), row("b", x), row("c", y)))
        val photos = database.rawPhotoDao().getByIds(listOf("a", "b", "c"))
        val change = CullEdits.resolve(CullEdit.SetLocation(shot), photos)!!

        repository.applyChange(change)
        listOf("a", "b", "c").forEach { assertEquals(it, shot, stored(it).location) }

        repository.applyChange(change.inverse())
        assertNull(stored("a").location)
        assertEquals(x, stored("b").location)
        assertEquals(y, stored("c").location)
    }

    @Test
    fun `an edit leaves only the location pending`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a")))
        apply(CullEdit.SetLocation(x), "a")
        assertEquals(XmpField.GPS.bit, stored("a").xmpPendingFields)
    }

    @Test
    fun `a photo already there isn't part of the change`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a", x), row("b")))
        val photos = database.rawPhotoDao().getByIds(listOf("a", "b"))
        val change = CullEdits.resolve(CullEdit.SetLocation(x), photos)!!
        assertEquals(listOf("b"), change.ids)
        assertEquals("Set the location of 1 photo", change.summary)
        assertNull(CullEdits.resolve(CullEdit.ClearLocation, database.rawPhotoDao().getByIds(listOf("b")).map { it.copy(gpsLatitude = null) }))
    }

    @Test
    fun `clearing a photo without a location in its file leaves none`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a", x)))
        apply(CullEdit.ClearLocation, "a")
        assertNull(stored("a").location)
        assertEquals(XmpField.GPS.bit, stored("a").xmpPendingFields)
    }

    @Test
    fun `clearing a photo whose file has a location shows the camera's again`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a", x, inFile = true), row("b", x, inFile = true)))
        camera["a"] = shot // b's file can't be read now
        apply(CullEdit.ClearLocation, "a", "b")
        assertEquals(shot, stored("a").location)
        assertNull(stored("b").location)
    }

    @Test
    fun `the sidecar gets the edited location, and removing it takes it away again`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a")))
        apply(CullEdit.SetLocation(x), "a")
        queue.flushNow()
        val uri = stored("a").xmpFileUri!!
        val written = XmpGps.read(XmpDocument.parse(files.getValue(uri)).getOrThrow())!!
        assertEquals(40.0, written.latitude, 1e-7)
        assertEquals(-79.0, written.longitude, 1e-7)
        assertEquals(0, stored("a").xmpPendingFields)

        apply(CullEdit.ClearLocation, "a")
        queue.flushNow()
        assertNull(XmpGps.read(XmpDocument.parse(files.getValue(uri)).getOrThrow()))
    }

    @Test
    fun `the camera's location is never copied into a sidecar`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a", shot, inFile = true)))
        camera["a"] = shot
        apply(CullEdit.SetLocation(shot.copy(latitude = shot.latitude + 1e-9)), "a")
        queue.flushNow()
        val written = XmpDocument.parse(files.getValue(stored("a").xmpFileUri!!)).getOrThrow()
        assertNull(XmpGps.read(written))
        assertEquals(shot.latitude, stored("a").location!!.latitude, 1e-6)
    }

    @Test
    fun `a rating alone creates a sidecar without the camera's location`() = runTest {
        database.rawPhotoDao().upsertAll(listOf(row("a", shot, inFile = true)))
        camera["a"] = shot
        apply(CullEdit.Rating(3), "a")
        queue.flushNow()
        val written = XmpDocument.parse(files.getValue(stored("a").xmpFileUri!!)).getOrThrow()
        assertNull(XmpGps.read(written))
        assertEquals(shot, stored("a").location)
    }

    @Test
    fun `the location filter and its counts`() {
        val rows = listOf(row("a", x), row("b"), row("c", y), row("d"))
        fun ids(filter: LocationFilter) =
            LibraryFilter.apply(rows, FilterOptions(locationFilter = filter), SortOrder.NAME_ASC, false)

        assertEquals(listOf("a", "c"), ids(LocationFilter.HAS).rows.map { it.id })
        assertEquals(listOf("b", "d"), ids(LocationFilter.NONE).rows.map { it.id })
        assertEquals(4, ids(LocationFilter.ANY).rows.size)
        // Counts ignore the filters.
        assertEquals(2, ids(LocationFilter.NONE).counts.withLocation)
        assertEquals(4, ids(LocationFilter.NONE).counts.total)
        assertTrue(FilterOptions(locationFilter = LocationFilter.HAS).isActive)
        assertFalse(FilterOptions().isActive)
        assertFalse(FilterOptions(locationFilter = LocationFilter.HAS).cleared().isActive)
    }

    @Test
    fun `the location filter is stored with the others and an unknown value reads as any`() {
        val filter = FilterOptions(locationFilter = LocationFilter.NONE)
        assertEquals(LocationFilter.NONE, FilterOptionsCodec.decode(FilterOptionsCodec.encode(filter)).locationFilter)
        assertEquals(LocationFilter.ANY, FilterOptionsCodec.decode("""{"locationFilter":"SOMEWHERE"}""").locationFilter)
        assertEquals(LocationFilter.ANY, FilterOptionsCodec.decode(FilterOptionsCodec.encode(FilterOptions())).locationFilter)
    }
}
