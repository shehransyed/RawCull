package dev.shehran.rawcull.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.data.xmp.XMP_PARSE_ERROR
import dev.shehran.rawcull.util.xmp.CullState
import dev.shehran.rawcull.util.xmp.XmpField
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The queue's safety rests on these statements, so they run against real SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XmpRowsDaoTest {
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

    private fun row(
        id: String = "a",
        status: XmpSyncStatus = XmpSyncStatus.SYNCED,
        error: String? = null,
        pending: Int = 0,
    ) = PhotoAsset(
        id = id,
        rootUri = "root",
        fileName = "$id.ARW",
        fileUri = "content://$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 0L,
        xmpSyncStatus = status,
        xmpError = error,
        xmpPendingFields = pending,
    )

    private suspend fun stored(id: String = "a") = dao.getPhotoById(id)!!

    @Test
    fun editsMarkTheRowPendingAndAccumulateFields() = runTest {
        dao.upsertAll(listOf(row("a"), row("b"), row("c")))

        dao.updateRating(listOf("a", "b"), 3)
        dao.updateCullStatus(listOf("a"), CullStatus.PICK)
        dao.updateColorLabel(listOf("a"), ColorLabel.RED)
        dao.updateTags("a", """["x"]""")

        assertEquals(XmpSyncStatus.PENDING, stored("a").xmpSyncStatus)
        assertEquals(XmpField.ALL_BITS, stored("a").xmpPendingFields)
        assertEquals(listOf("x"), stored("a").tags)
        assertEquals(XmpField.RATING.bit, stored("b").xmpPendingFields)
        assertEquals(XmpSyncStatus.SYNCED, stored("c").xmpSyncStatus)
    }

    @Test
    fun finishSyncedClearsPendingState() = runTest {
        dao.upsertAll(listOf(row(status = XmpSyncStatus.WRITING, error = "old", pending = XmpField.RATING.bit)))

        dao.finishSynced("a", "content://a.xmp", 123L, null)

        val row = stored()
        assertEquals(XmpSyncStatus.SYNCED, row.xmpSyncStatus)
        assertEquals("content://a.xmp", row.xmpFileUri)
        assertEquals(123L, row.xmpLastSyncedMtime)
        assertEquals(0, row.xmpPendingFields)
        assertNull(row.xmpError)
    }

    @Test
    fun finishSyncedAppliesTheFileState() = runTest {
        dao.upsertAll(listOf(row(status = XmpSyncStatus.WRITING)))

        dao.finishSynced("a", "uri", 1L, CullState(4, CullStatus.REJECT, ColorLabel.BLUE, listOf("t")))

        val row = stored()
        assertEquals(4, row.rating)
        assertEquals(CullStatus.REJECT, row.cullStatus)
        assertEquals(ColorLabel.BLUE, row.colorLabel)
        assertEquals(listOf("t"), row.tags)
    }

    @Test
    fun anEditDuringTheWriteSurvivesTheEndOfThatWrite() = runTest {
        dao.upsertAll(listOf(row()))
        dao.updateRating(listOf("a"), 1)
        dao.markWriting("a")
        dao.updateRating(listOf("a"), 5) // Lands while the file is being written.

        dao.finishSynced("a", "uri", 77L, CullState(1, CullStatus.UNFLAGGED, ColorLabel.NONE, emptyList()))

        val row = stored()
        assertEquals("not overwritten by the older state", 5, row.rating)
        assertEquals("still has to be written", XmpSyncStatus.PENDING, row.xmpSyncStatus)
        assertEquals(XmpField.RATING.bit, row.xmpPendingFields)
        assertEquals("the write itself still happened", 77L, row.xmpLastSyncedMtime)
        assertEquals("uri", row.xmpFileUri)
    }

    @Test
    fun aFailureDoesNotOverrideAnEditDuringTheWrite() = runTest {
        dao.upsertAll(listOf(row()))
        dao.markWriting("a")
        dao.updateRating(listOf("a"), 5)

        dao.finishFailed("a", XmpSyncStatus.ERROR, "Boom")

        assertEquals(XmpSyncStatus.PENDING, stored().xmpSyncStatus)
        assertNull(stored().xmpError)
    }

    @Test
    fun aFailureKeepsThePendingFields() = runTest {
        dao.upsertAll(listOf(row(status = XmpSyncStatus.WRITING, pending = XmpField.LABEL.bit)))

        dao.finishFailed("a", XmpSyncStatus.READ_ONLY, null)

        assertEquals(XmpSyncStatus.READ_ONLY, stored().xmpSyncStatus)
        assertEquals(XmpField.LABEL.bit, stored().xmpPendingFields)
    }

    @Test
    fun recoveryIncludesUnfinishedRowsButNotParseErrorsOrSettledOnes() = runTest {
        dao.upsertAll(
            listOf(
                row("pending", XmpSyncStatus.PENDING),
                row("writing", XmpSyncStatus.WRITING),
                row("ioError", XmpSyncStatus.ERROR, "Card went away"),
                row("noMessage", XmpSyncStatus.ERROR),
                row("parseError", XmpSyncStatus.ERROR, XMP_PARSE_ERROR),
                row("synced", XmpSyncStatus.SYNCED),
                row("readOnly", XmpSyncStatus.READ_ONLY),
            ),
        )

        assertEquals(
            setOf("pending", "writing", "ioError", "noMessage"),
            dao.recoverableIds(XMP_PARSE_ERROR).toSet(),
        )
    }

    @Test
    fun markPendingClearsTheErrorAndAddsFields() = runTest {
        dao.upsertAll(listOf(row(status = XmpSyncStatus.ERROR, error = "Boom", pending = XmpField.RATING.bit)))

        dao.markPending("a", XmpField.TAGS.bit)

        assertEquals(XmpSyncStatus.PENDING, stored().xmpSyncStatus)
        assertNull(stored().xmpError)
        assertEquals(XmpField.RATING.bit or XmpField.TAGS.bit, stored().xmpPendingFields)
    }

    @Test
    fun forgetSidecarsClearsOnlyTheGivenUris() = runTest {
        dao.upsertAll(listOf(row().copy(xmpFileUri = "good", darktableXmpUri = "bad")))

        dao.forgetSidecars("a", listOf("bad"))

        assertEquals("good", stored().xmpFileUri)
        assertNull(stored().darktableXmpUri)
    }
}
