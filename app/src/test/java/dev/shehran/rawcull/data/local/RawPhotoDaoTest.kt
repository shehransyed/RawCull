package dev.shehran.rawcull.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.EnrichedMetadata
import dev.shehran.rawcull.data.model.EnrichedXmp
import dev.shehran.rawcull.data.model.EnrichmentResult
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.data.xmp.XMP_PARSE_ERROR
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.model.RecentSource
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
class RawPhotoDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RawPhotoDao
    private val converters = Converters()

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

    private fun row(id: String = "a", root: String = "root", lastModified: Long = 0L) = PhotoAsset(
        id = id,
        rootUri = root,
        fileName = "$id.ARW",
        fileUri = "content://$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 100L,
        lastModified = lastModified,
    )

    private fun metadata(id: String = "a") = EnrichedMetadata(
        id = id,
        dateTaken = 200L,
        width = 6000,
        height = 4000,
        cameraMake = "SONY",
        cameraModel = "ILCE-7M4",
        lensModel = null,
        shutterSpeed = "1/200s",
        aperture = "f/2.8",
        iso = "100",
        focalLength = "50 mm",
        exposureBias = null,
        orientation = 1,
    )

    private fun xmp(lastModified: Long, rating: Int = 3, cullStatus: CullStatus? = CullStatus.PICK) = EnrichedXmp(
        rating = rating,
        cullStatus = cullStatus,
        colorLabel = ColorLabel.BLUE,
        tagsJson = converters.fromTagsList(listOf("from-xmp")),
        lastModified = lastModified,
    )

    @Test
    fun `a metadata batch leaves user state alone`() = runTest {
        dao.upsertAll(listOf(row(lastModified = 500L)))
        dao.updateRating(listOf("a"), 5, time = 600L)

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp = null)))

        val stored = dao.getPhotoById("a")!!
        assertEquals(5, stored.rating)
        assertEquals(200L, stored.dateTaken)
        assertEquals("ILCE-7M4", stored.cameraModel)
        assertTrue(stored.metadataLoaded)
    }

    @Test
    fun `a sidecar wins on a new row`() = runTest {
        dao.upsertAll(listOf(row(lastModified = 0L)))

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp(lastModified = 50L))))

        val stored = dao.getPhotoById("a")!!
        assertEquals(3, stored.rating)
        assertEquals(CullStatus.PICK, stored.cullStatus)
        assertEquals(ColorLabel.BLUE, stored.colorLabel)
        assertEquals(listOf("from-xmp"), stored.tags)
        assertEquals(50L, stored.xmpLastSyncedMtime)
    }

    @Test
    fun `a sidecar wins over a row with no edits of its own`() = runTest {
        dao.upsertAll(listOf(row(lastModified = 500L)))

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp(lastModified = 900L))))

        assertEquals(3, dao.getPhotoById("a")!!.rating)
    }

    @Test
    fun `a flag set during enrichment beats the sidecar`() = runTest {
        dao.upsertAll(listOf(row(lastModified = 0L)))
        dao.updateCullStatus(listOf("a"), CullStatus.REJECT, time = 1_000L)

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp(lastModified = 900L))))

        val stored = dao.getPhotoById("a")!!
        assertEquals(CullStatus.REJECT, stored.cullStatus)
        assertEquals(0, stored.rating)
        assertTrue("metadata still lands", stored.metadataLoaded)
    }

    @Test
    fun `a sidecar with no pick keeps the row's pick but its stars win`() = runTest {
        dao.upsertAll(listOf(row(lastModified = 0L).copy(rating = 2, cullStatus = CullStatus.PICK)))

        dao.updateMetadataBatch(
            listOf(EnrichmentResult(metadata(), xmp(lastModified = 50L, rating = 0, cullStatus = null)))
        )

        val stored = dao.getPhotoById("a")!!
        assertEquals(0, stored.rating)
        assertEquals(CullStatus.PICK, stored.cullStatus)
    }

    @Test
    fun `a sidecar clears an earlier parse error`() = runTest {
        dao.upsertAll(listOf(row().copy(xmpSyncStatus = XmpSyncStatus.ERROR, xmpError = XMP_PARSE_ERROR)))

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp(lastModified = 50L))))

        val stored = dao.getPhotoById("a")!!
        assertEquals(XmpSyncStatus.SYNCED, stored.xmpSyncStatus)
        assertNull(stored.xmpError)
    }

    @Test
    fun `an unreadable sidecar is reported and changes nothing else`() = runTest {
        dao.upsertAll(listOf(row().copy(rating = 2)))

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp = null, sidecarUnreadable = true)))

        val stored = dao.getPhotoById("a")!!
        assertEquals(XmpSyncStatus.ERROR, stored.xmpSyncStatus)
        assertEquals(XMP_PARSE_ERROR, stored.xmpError)
        assertEquals(2, stored.rating)
    }

    @Test
    fun `an unreadable sidecar doesn't disturb a row the queue is about to write`() = runTest {
        dao.upsertAll(listOf(row()))
        dao.updateRating(listOf("a"), 3)

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp = null, sidecarUnreadable = true)))

        assertEquals(XmpSyncStatus.PENDING, dao.getPhotoById("a")!!.xmpSyncStatus)
    }

    @Test
    fun `edits that could not be written survive a sidecar`() = runTest {
        dao.upsertAll(listOf(row().copy(rating = 5, xmpSyncStatus = XmpSyncStatus.READ_ONLY, xmpPendingFields = 1)))

        dao.updateMetadataBatch(listOf(EnrichmentResult(metadata(), xmp(lastModified = 50L))))

        assertEquals(5, dao.getPhotoById("a")!!.rating)
    }

    @Test
    fun `queries are scoped to one root`() = runTest {
        dao.upsertAll(listOf(row("a", "card"), row("b", "demo")))

        assertEquals(listOf("a"), dao.getByRoot("card").map { it.id })
        assertEquals(listOf("b"), dao.getIdsByRoot("demo"))
    }

    @Test
    fun `deleteByIds removes only those rows`() = runTest {
        dao.upsertAll(listOf(row("a"), row("b")))

        dao.deleteByIds(listOf("a"))

        assertNull(dao.getPhotoById("a"))
        assertEquals("b", dao.getPhotoById("b")?.id)
    }

    @Test
    fun `recent sources survive the JSON round trip`() {
        val recents = listOf(RecentSource("content://a", "Card \"A\"", 123L))

        val decoded = SettingsRepository.decodeRecents(SettingsRepository.encodeRecents(recents))

        assertEquals(recents, decoded)
    }

    @Test
    fun `corrupt recents decode to an empty list`() {
        assertEquals(emptyList<RecentSource>(), SettingsRepository.decodeRecents("not json"))
    }
}
