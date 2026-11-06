package dev.shehran.rawcull.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetIdsTest {
    private val root = "content://tree/card"

    @Test
    fun `the same inputs give the same id`() {
        assertEquals(AssetIds.of(root, "DCIM/100", "DSC001"), AssetIds.of(root, "DCIM/100", "DSC001"))
    }

    @Test
    fun `ids are 40 character hex`() {
        assertTrue(AssetIds.of(root, "", "DSC001").matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun `a different relativeDir gives a different id`() {
        assertNotEquals(AssetIds.of(root, "DCIM/100", "DSC001"), AssetIds.of(root, "DCIM/101", "DSC001"))
    }

    @Test
    fun `a different root gives a different id`() {
        assertNotEquals(AssetIds.of(root, "", "DSC001"), AssetIds.of("content://tree/other", "", "DSC001"))
    }

    @Test
    fun `dir and stem are case-insensitive`() {
        assertEquals(AssetIds.of(root, "DCIM/100", "DSC001"), AssetIds.of(root, "dcim/100", "dsc001"))
    }

    @Test
    fun `a discriminator gives a different id`() {
        assertNotEquals(AssetIds.of(root, "", "DSC001"), AssetIds.of(root, "", "DSC001", "dng"))
    }
}

class PhotoFormatTest {

    @Test
    fun `every planned extension is recognised case-insensitively`() {
        val expected = mapOf(
            "ARW" to PhotoFormat.ARW, "DNG" to PhotoFormat.DNG, "CR2" to PhotoFormat.CR2, "CR3" to PhotoFormat.CR3,
            "NEF" to PhotoFormat.NEF, "NRW" to PhotoFormat.NRW, "RAF" to PhotoFormat.RAF, "ORF" to PhotoFormat.ORF,
            "RW2" to PhotoFormat.RW2, "PEF" to PhotoFormat.PEF, "SRW" to PhotoFormat.SRW,
            "JPG" to PhotoFormat.JPEG, "JPEG" to PhotoFormat.JPEG,
            "HEIC" to PhotoFormat.HEIF, "HEIF" to PhotoFormat.HEIF, "HIF" to PhotoFormat.HEIF,
        )
        expected.forEach { (extension, format) ->
            assertEquals(format, PhotoFormat.fromFileNameOrNull("a.$extension"))
            assertEquals(format, PhotoFormat.fromFileNameOrNull("a.${extension.lowercase()}"))
        }
    }

    @Test
    fun `anything else is not a photo`() {
        listOf("a.MP4", "a.THM", "a.xmp", "a.txt", "ARW").forEach { assertNull(it, PhotoFormat.fromFileNameOrNull(it)) }
    }

    @Test
    fun `mime types follow the plan`() {
        assertEquals("image/x-canon-cr3", PhotoFormat.CR3.mimeType)
        assertEquals("application/octet-stream", PhotoFormat.RAW_OTHER.mimeType)
    }
}

class SidecarFileNameTest {
    private val root = "content://tree/card"

    private fun asset(id: String, fileName: String) = PhotoAsset(
        id = id,
        rootUri = root,
        relativeDir = "DCIM",
        fileName = fileName,
        fileUri = "content://doc/$fileName",
        format = PhotoFormat.fromFileName(fileName),
        fileSizeBytes = 1L,
        dateTaken = 0L,
    )

    @Test
    fun `the first asset of a stem group owns the Adobe-style name`() {
        val photo = asset(AssetIds.of(root, "DCIM", "DSC001"), "DSC001.ARW")

        assertEquals("DSC001.xmp", photo.sidecarFileName)
    }

    @Test
    fun `other assets of the group use the darktable-style name`() {
        val photo = asset(AssetIds.of(root, "DCIM", "DSC001", "dng"), "DSC001.DNG")

        assertEquals("DSC001.DNG.xmp", photo.sidecarFileName)
    }
}

class RecentSourcesTest {
    private fun source(uri: String, at: Long = 0L) = RecentSource(uri, uri, at)

    @Test
    fun `the newest entry comes first`() {
        val update = RecentSources.add(listOf(source("a")), source("b"))

        assertEquals(listOf("b", "a"), update.recents.map { it.uri })
    }

    @Test
    fun `reopening a source moves it to the front without duplicating it`() {
        val update = RecentSources.add(listOf(source("a"), source("b", 1L)), source("b", 2L))

        assertEquals(listOf("b", "a"), update.recents.map { it.uri })
        assertEquals(2L, update.recents.first().lastOpenedAt)
        assertTrue(update.evicted.isEmpty())
    }

    @Test
    fun `the ninth source evicts the oldest`() {
        val full = (1..8).map { source("s$it") }

        val update = RecentSources.add(full, source("new"))

        assertEquals(RecentSources.MAX_ENTRIES, update.recents.size)
        assertEquals(listOf("s8"), update.evicted.map { it.uri })
    }
}
