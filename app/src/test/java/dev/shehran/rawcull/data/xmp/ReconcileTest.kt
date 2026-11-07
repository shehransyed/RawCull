package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.xmp.XmpField
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
