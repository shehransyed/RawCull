package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpField
import dev.shehran.rawcull.util.xmp.XmpFixtures
import dev.shehran.rawcull.util.xmp.XmpPrefs
import dev.shehran.rawcull.util.xmp.XmpProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ReconcileTest {
    private fun row(
        status: XmpSyncStatus = XmpSyncStatus.SYNCED,
        synced: Long? = 100L,
        pending: Int = 0,
    ) = PhotoAsset(
        id = "a",
        rootUri = "root",
        fileName = "a.ARW",
        fileUri = "content://a",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 0L,
        xmpSyncStatus = status,
        xmpLastSyncedMtime = synced,
        xmpPendingFields = pending,
    )

    @Test
    fun aPendingRowKeepsTheCatalogValues() {
        assertEquals(SidecarAction.KEEP_CATALOG, SidecarReconciler.actionFor(row(XmpSyncStatus.PENDING), 900L))
        assertEquals(SidecarAction.KEEP_CATALOG, SidecarReconciler.actionFor(row(XmpSyncStatus.WRITING), 900L))
    }

    @Test
    fun editsThatCouldNotBeWrittenAreNotDropped() {
        val readOnly = row(XmpSyncStatus.READ_ONLY, pending = XmpField.RATING.bit)
        assertEquals(SidecarAction.KEEP_CATALOG, SidecarReconciler.actionFor(readOnly, 900L))
    }

    @Test
    fun aNewerFileWins() {
        assertEquals(SidecarAction.READ_FILE, SidecarReconciler.actionFor(row(), 900L))
    }

    @Test
    fun anyDifferentTimeCountsBecauseRestoredFilesCanBeOlder() {
        assertEquals(SidecarAction.READ_FILE, SidecarReconciler.actionFor(row(), 50L))
    }

    @Test
    fun aRowThatWasNeverSyncedReadsTheFile() {
        assertEquals(SidecarAction.READ_FILE, SidecarReconciler.actionFor(row(synced = null), 900L))
    }

    @Test
    fun anUnchangedFileIsNotReadAgain() {
        assertEquals(SidecarAction.NOTHING, SidecarReconciler.actionFor(row(), 100L))
    }

    private val prefs = XmpPrefs()

    private fun export(row: PhotoAsset, source: ByteArray?) = XmpDocument.parse(
        SidecarReconciler.exportBytes(row, source, prefs, "RawCull test", Date(0L))
    ).getOrThrow()

    @Test
    fun exportCarriesDevelopSettingsAndTheCurrentState() {
        val row = row().copy(metadataLoaded = true, rating = 2, cullStatus = CullStatus.PICK, colorLabel = ColorLabel.RED)
        val bytes = SidecarReconciler.exportBytes(row, XmpFixtures.bytes(XmpFixtures.LIGHTROOM), prefs, "t", Date(0L))
        val text = bytes.toString(Charsets.UTF_8)
        val doc = XmpDocument.parse(bytes).getOrThrow()

        assertEquals(2, doc.rating)
        assertEquals("Red", doc.label)
        assertEquals(1, doc.pick)
        assertTrue("crs:Exposure2012=\"+0.45\"" in text)
        assertTrue("Events|Wedding" in text)
    }

    @Test
    fun exportWithoutASidecarWritesANewDocument() {
        val doc = export(row().copy(rating = 5, tags = listOf("R&D <test>")), null)
        assertEquals(5, doc.rating)
        assertEquals(listOf("R&D <test>"), doc.subjects)
    }

    @Test
    fun exportOfAnUnreadableSourceFallsBackToANewDocument() {
        val doc = export(row().copy(rating = 5), XmpFixtures.bytes(XmpFixtures.MALFORMED))
        assertEquals(5, doc.rating)
    }

    @Test
    fun exportOfARowTheScanHasNotReadKeepsTheFilesValues() {
        val row = row().copy(metadataLoaded = false, colorLabel = ColorLabel.RED, xmpPendingFields = XmpField.LABEL.bit)
        val doc = export(row, XmpFixtures.bytes(XmpFixtures.LIGHTROOM))

        assertEquals("Red", doc.label)
        assertEquals(4, doc.rating)
        assertEquals(listOf("Wedding", "Dhaka", "Golden Hour"), doc.subjects)
    }

    @Test
    fun exportMigratesCrsPick() {
        val doc = export(row().copy(metadataLoaded = true, cullStatus = CullStatus.PICK), XmpFixtures.bytes(XmpFixtures.LEGACY))
        assertFalse(doc.has(XmpProps.CRS_PICK))
    }
}
