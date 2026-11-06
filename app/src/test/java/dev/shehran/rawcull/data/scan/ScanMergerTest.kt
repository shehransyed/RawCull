package dev.shehran.rawcull.data.scan

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.xmp.XmpField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMergerTest {
    private val root = "content://tree/card"

    private fun scanned(name: String, lastModified: Long = 1_000L) = ScannedEntry(
        docId = name,
        docUri = "content://doc/$name",
        parentDocId = "dir",
        parentDocUri = "content://doc/dir",
        relativeDir = "DCIM",
        name = name,
        mime = null,
        size = 42L,
        lastModified = lastModified,
    )

    private fun draft(
        name: String = "DSC001.ARW",
        lastModified: Long = 1_000L,
        sidecar: ScannedEntry? = null,
    ) = AssetDraft(
        primary = scanned(name, lastModified),
        format = PhotoFormat.ARW,
        pairMode = PairMode.RAW_ONLY,
        adobeXmp = sidecar,
    )

    /** What a fully enriched, user-edited row for [draft] looks like. */
    private fun storedRow(source: AssetDraft = draft()): PhotoAsset =
        ScanMerger.merge(root, emptyMap(), listOf(source)).toUpsert.single().copy(
            cullStatus = CullStatus.PICK,
            rating = 4,
            colorLabel = ColorLabel.GREEN,
            tags = listOf("keeper"),
            cameraModel = "ILCE-7M4",
            dateTaken = 5_000L,
            metadataLoaded = true,
            lastModified = 9_000L,
        )

    @Test
    fun `new files get defaults`() {
        val row = ScanMerger.merge(root, emptyMap(), listOf(draft())).toUpsert.single()

        assertEquals(1_000L, row.dateTaken)
        assertEquals(1_000L, row.fileLastModified)
        assertFalse(row.metadataLoaded)
        assertEquals(CullStatus.UNFLAGGED, row.cullStatus)
        assertEquals("DCIM", row.relativeDir)
        assertEquals("content://doc/dir", row.parentDocUri)
        assertEquals(root, row.rootUri)
    }

    @Test
    fun `an unchanged row is not rewritten`() {
        val stored = storedRow()

        val result = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(draft()))

        assertTrue(result.toUpsert.isEmpty())
        assertTrue(result.toDelete.isEmpty())
    }

    @Test
    fun `user state and metadata survive a rescan that changes the row`() {
        val stored = storedRow()
        val moved = draft().copy(primary = scanned("DSC001.ARW").copy(docUri = "content://doc/moved"))

        val row = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(moved)).toUpsert.single()

        assertEquals("content://doc/moved", row.fileUri)
        assertEquals(CullStatus.PICK, row.cullStatus)
        assertEquals(4, row.rating)
        assertEquals(ColorLabel.GREEN, row.colorLabel)
        assertEquals(listOf("keeper"), row.tags)
        assertEquals("ILCE-7M4", row.cameraModel)
        assertEquals(5_000L, row.dateTaken)
        assertTrue(row.metadataLoaded)
    }

    @Test
    fun `a changed file is re-read but keeps its user state`() {
        val stored = storedRow()

        val row = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(draft(lastModified = 2_000L)))
            .toUpsert.single()

        assertFalse(row.metadataLoaded)
        assertEquals(2_000L, row.fileLastModified)
        assertEquals(CullStatus.PICK, row.cullStatus)
        assertEquals(4, row.rating)
    }

    @Test
    fun `a sidecar that changed since the last sync triggers a re-read`() {
        val stored = storedRow().copy(xmpLastSyncedMtime = 8_000L)
        val edited = draft(sidecar = scanned("DSC001.xmp", lastModified = 10_000L))

        val result = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(edited))

        assertFalse(result.toUpsert.single().metadataLoaded)
        assertEquals(10_000L, result.sidecarLastModified[stored.id])
    }

    @Test
    fun `a sidecar that hasn't changed does not`() {
        val stored = storedRow().copy(xmpLastSyncedMtime = 8_000L)
        val old = draft(sidecar = scanned("DSC001.xmp", lastModified = 8_000L))

        val row = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(old)).toUpsert.single()

        assertTrue(row.metadataLoaded)
        assertEquals("content://doc/DSC001.xmp", row.xmpFileUri)
    }

    @Test
    fun `an older restored sidecar counts as changed`() {
        val stored = storedRow().copy(xmpLastSyncedMtime = 8_000L)
        val restored = draft(sidecar = scanned("DSC001.xmp", lastModified = 3_000L))

        assertFalse(ScanMerger.merge(root, mapOf(stored.id to stored), listOf(restored)).toUpsert.single().metadataLoaded)
    }

    @Test
    fun `a deleted sidecar forgets its sync time and keeps the row's values`() {
        val stored = storedRow().copy(xmpFileUri = "content://doc/DSC001.xmp", xmpLastSyncedMtime = 8_000L)

        val row = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(draft())).toUpsert.single()

        assertNull(row.xmpFileUri)
        assertNull(row.xmpLastSyncedMtime)
        assertTrue(row.metadataLoaded)
        assertEquals(4, row.rating)
    }

    @Test
    fun `sync state survives a rescan`() {
        val stored = storedRow().copy(
            xmpSyncStatus = XmpSyncStatus.PENDING,
            xmpPendingFields = XmpField.RATING.bit,
        )

        val row = ScanMerger.merge(root, mapOf(stored.id to stored), listOf(draft(lastModified = 2_000L))).toUpsert.single()

        assertEquals(XmpSyncStatus.PENDING, row.xmpSyncStatus)
        assertEquals(XmpField.RATING.bit, row.xmpPendingFields)
    }

    @Test
    fun `a darktable sidecar is tracked alongside the adobe one`() {
        val adobe = scanned("DSC001.xmp", lastModified = 1L)
        val darktable = scanned("DSC001.ARW.xmp", lastModified = 1L)

        val row = ScanMerger.merge(root, emptyMap(), listOf(draft(sidecar = adobe).copy(darktableXmp = darktable)))
            .toUpsert.single()

        assertEquals("content://doc/DSC001.xmp", row.xmpFileUri)
        assertEquals("content://doc/DSC001.ARW.xmp", row.darktableXmpUri)
    }

    @Test
    fun `removed files are deleted`() {
        val stored = storedRow()

        val result = ScanMerger.merge(root, mapOf(stored.id to stored), emptyList())

        assertEquals(listOf(stored.id), result.toDelete)
    }

    @Test
    fun `files that remain are not deleted`() {
        val kept = storedRow()
        val gone = storedRow(draft("DSC002.ARW"))

        val result = ScanMerger.merge(root, mapOf(kept.id to kept, gone.id to gone), listOf(draft()))

        assertEquals(listOf(gone.id), result.toDelete)
    }
}
