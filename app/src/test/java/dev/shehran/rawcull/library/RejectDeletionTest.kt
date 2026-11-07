package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectDeletionTest {
    private fun row(
        id: String,
        companion: String? = null,
        xmp: String? = null,
        darktable: String? = null,
        size: Long = 1000,
    ) = PhotoAsset(
        id = id,
        rootUri = "root",
        fileName = "$id.ARW",
        fileUri = "doc/$id.ARW",
        format = PhotoFormat.ARW,
        fileSizeBytes = size,
        dateTaken = 0,
        companionFileUri = companion,
        companionFileSizeBytes = companion?.let { 500L },
        xmpFileUri = xmp,
        darktableXmpUri = darktable,
    )

    private val everything: (String) -> String? = { "media/$it" }

    @Test
    fun `every file of a group is listed`() {
        val files = RejectDeletion.filesOf(row("a", companion = "doc/a.JPG", xmp = "doc/a.xmp", darktable = "doc/a.ARW.xmp"))
        assertEquals(listOf("doc/a.ARW", "doc/a.JPG", "doc/a.xmp", "doc/a.ARW.xmp"), files.map { it.documentUri })
        assertEquals(listOf(true, false, false, false), files.map { it.isPrimary })
    }

    @Test
    fun `one sidecar referenced twice is listed once`() {
        assertEquals(2, RejectDeletion.filesOf(row("a", xmp = "doc/a.xmp", darktable = "doc/a.xmp")).size)
    }

    @Test
    fun `a blank companion isn't a file`() {
        assertEquals(1, RejectDeletion.filesOf(row("a", companion = "")).size)
    }

    @Test
    fun `resolved files go to the trash from Android 11`() {
        val plan = RejectDeletion.plan(listOf(row("a", xmp = "doc/a.xmp")), apiLevel = 30, resolveMediaUri = everything)
        assertEquals(2, plan.toTrash.size)
        assertTrue(plan.toDelete.isEmpty())
        assertFalse(plan.isAllPermanent)
    }

    @Test
    fun `before Android 11 everything is permanent, resolvable or not`() {
        val plan = RejectDeletion.plan(listOf(row("a")), apiLevel = 29, resolveMediaUri = everything)
        assertTrue(plan.toTrash.isEmpty())
        assertTrue(plan.isAllPermanent)
    }

    @Test
    fun `files MediaStore doesn't know are permanent`() {
        val plan = RejectDeletion.plan(listOf(row("a", xmp = "doc/a.xmp")), apiLevel = 36) { uri ->
            uri.takeIf { it.endsWith(".ARW") }
        }
        assertEquals(listOf("doc/a.ARW"), plan.toTrash.map { it.documentUri })
        assertEquals(listOf("doc/a.xmp"), plan.toDelete.map { it.documentUri })
        assertFalse(plan.isAllPermanent)
    }

    @Test
    fun `totals add up`() {
        val plan = RejectDeletion.plan(
            listOf(row("a", companion = "doc/a.JPG", xmp = "doc/a.xmp"), row("b")),
            apiLevel = 36,
            resolveMediaUri = everything,
        )
        assertEquals(2, plan.photoCount)
        assertEquals(4, plan.fileCount)
        assertEquals(2500L, plan.totalBytes)
    }

    @Test
    fun `the confirmation says where things go`() {
        val mixed = RejectDeletion.plan(listOf(row("a", xmp = "doc/a.xmp", size = 2L * 1024 * 1024 * 1024)), 36) { uri ->
            uri.takeIf { it.endsWith(".ARW") }
        }
        assertEquals("Delete 1 rejected photo?", RejectDeletion.title(mixed))
        assertEquals(
            "2 files · 2.0 GB. 1 goes to the device trash (restorable for 30 days). 1 will be deleted permanently.",
            RejectDeletion.detail(mixed),
        )

        val permanent = RejectDeletion.plan(listOf(row("a"), row("b")), 29) { null }
        assertEquals("Delete 2 rejected photos?", RejectDeletion.title(permanent))
        assertEquals("2 files · 1 KB. This can't be undone.", RejectDeletion.detail(permanent))
    }

    @Test
    fun `a photo stays listed only if its primary file couldn't go`() {
        val plan = RejectDeletion.plan(listOf(row("a", xmp = "doc/a.xmp"), row("b")), 29) { null }

        val sidecarFailed = RejectDeletion.outcome(plan, setOf("doc/a.xmp"))
        assertEquals(listOf("a", "b"), sidecarFailed.removedIds)
        assertEquals(1, sidecarFailed.failedFiles)

        assertEquals(listOf("b"), RejectDeletion.outcome(plan, setOf("doc/a.ARW")).removedIds)
    }

    @Test
    fun `a group can be taken out of a plan whole`() {
        val plan = RejectDeletion.plan(listOf(row("a", xmp = "doc/a.xmp"), row("b")), 36, everything)
        val rest = plan.withoutPhotos(setOf("a"))
        assertEquals(listOf("b"), rest.photoIds)
        assertEquals(listOf("doc/b.ARW"), rest.files.map { it.documentUri })
    }

    @Test
    fun `the demo plan has rows and no files`() {
        val plan = RejectDeletion.rowsOnly(listOf(row("a")))
        assertEquals(listOf("a"), plan.photoIds)
        assertTrue(plan.files.isEmpty())
        assertFalse(plan.isAllPermanent)
        assertEquals("They'll be removed from the demo library.", RejectDeletion.detail(plan))
    }

    @Test
    fun `the common folder is the deepest one they share`() {
        assertEquals("2026", RejectDeletion.commonFolder(listOf("2026/01", "2026/02")))
        assertEquals("2026/01", RejectDeletion.commonFolder(listOf("2026/01", "2026/01")))
        assertEquals("", RejectDeletion.commonFolder(listOf("2025/12", "2026/01")))
        assertEquals("", RejectDeletion.commonFolder(emptyList()))
    }
}
