package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ExportPlanTest {
    private val now = 1_800_000_000_000L
    private val base = 1_700_000_000_000L

    private fun raw(stem: String, date: Long = base, jpeg: Boolean = false, sidecar: Boolean = true) = PhotoAsset(
        id = AssetIds.of("tree", "", stem),
        rootUri = "tree",
        fileName = "$stem.ARW",
        fileUri = "doc/$stem.ARW",
        format = PhotoFormat.ARW,
        fileSizeBytes = 10,
        dateTaken = date,
        companionFileName = if (jpeg) "$stem.JPG" else null,
        companionFileUri = if (jpeg) "doc/$stem.JPG" else null,
        companionFormat = if (jpeg) PhotoFormat.JPEG else null,
        xmpFileUri = if (sidecar) "doc/$stem.xmp" else null,
    )

    private fun jpeg(stem: String) = PhotoAsset(
        id = AssetIds.of("tree", "", stem),
        rootUri = "tree",
        fileName = "$stem.JPG",
        fileUri = "doc/$stem.JPG",
        format = PhotoFormat.JPEG,
        fileSizeBytes = 10,
        dateTaken = base,
    )

    private fun plan(photos: List<PhotoAsset>, content: ExportContent = ExportContent.ORIGINALS, keepOrder: Boolean = true) =
        ExportPlanner.plan(photos, content, keepOrder, now)

    @Test
    fun `numbers are padded to three digits, or more for big exports`() {
        assertEquals(3, ExportPlanner.digitsFor(9))
        assertEquals(3, ExportPlanner.digitsFor(10))
        assertEquals(3, ExportPlanner.digitsFor(999))
        assertEquals(4, ExportPlanner.digitsFor(1000))

        val nine = plan((1..9).map { raw("P$it", sidecar = false) }, keepOrder = false)
        assertEquals(listOf("001_P1.ARW"), nine.names(nine.items.first(), 0))
        assertEquals(listOf("009_P9.ARW"), nine.names(nine.items.last(), 0))
        val ten = plan((1..10).map { raw("P$it", sidecar = false) }, keepOrder = false)
        assertEquals(listOf("010_P10.ARW"), ten.names(ten.items.last(), 0))
        val many = plan((1..999).map { raw("P$it", sidecar = false) }, keepOrder = false)
        assertEquals(listOf("999_P999.ARW"), many.names(many.items.last(), 0))
        val thousand = plan((1..1000).map { raw("P$it", sidecar = false) }, keepOrder = false)
        assertEquals(listOf("0001_P1.ARW"), thousand.names(thousand.items.first(), 0))
        assertEquals(listOf("1000_P1000.ARW"), thousand.names(thousand.items.last(), 0))
    }

    @Test
    fun `a group's files and sidecar share one number and suffix`() {
        val result = plan(listOf(raw("DSC1", jpeg = true), raw("DSC2", jpeg = true)))
        val second = result.items[1]
        assertEquals(listOf("002_DSC2.ARW", "002_DSC2.JPG", "002_DSC2.xmp"), result.names(second, 0))
        assertEquals(listOf("002_DSC2-1.ARW", "002_DSC2-1.JPG", "002_DSC2-1.xmp"), result.names(second, 1))
        assertEquals(listOf(ExportFileKind.BYTES, ExportFileKind.JPEG, ExportFileKind.SIDECAR), second.files.map { it.kind })
    }

    @Test
    fun `a RAW without a sidecar gets a new one only when the order is kept`() {
        assertEquals(ExportFileKind.NEW_SIDECAR, plan(listOf(raw("A", sidecar = false))).items[0].files.last().kind)
        assertEquals(1, plan(listOf(raw("A", sidecar = false)), keepOrder = false).items[0].files.size)
        // A JPEG carries the time in its own EXIF.
        assertEquals(listOf(ExportFileKind.JPEG), plan(listOf(jpeg("J"))).items[0].files.map { it.kind })
    }

    @Test
    fun `JPEGs only takes the companion or the JPEG and lists the rest as skipped`() {
        val result = plan(listOf(raw("A", jpeg = true), raw("B"), jpeg("C")), ExportContent.JPEGS_ONLY)
        assertEquals(listOf("001_A.JPG"), result.names(result.items[0], 0))
        assertEquals(listOf("002_C.JPG"), result.names(result.items[1], 0))
        assertEquals(listOf("B.ARW"), result.skipped)
    }

    @Test
    fun `kept order counts from the first photo's time in 10 s steps`() {
        val result = plan(listOf(raw("A", date = base + 5_000), raw("B", date = base - 99_000), raw("C", date = 0)))
        assertEquals(listOf(base + 5_000, base + 15_000, base + 25_000), result.items.map { it.captureTime })
        assertNull(plan(listOf(raw("A")), keepOrder = false).items[0].captureTime)
        // An undated first photo starts from now.
        assertEquals(now, plan(listOf(raw("U", date = 0))).items[0].captureTime)
    }

    @Test
    fun `times are written as EXIF and XMP want them, keeping an XMP offset`() {
        val utc = TimeZone.getTimeZone("UTC")
        val time = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        assertEquals("2023:11:14 22:13:20", ExportTimes.exif(time, utc))
        assertEquals("2023-11-14T22:13:20", ExportTimes.xmp(time, null, utc))
        assertEquals("2023-11-14T22:13:20+02:00", ExportTimes.xmp(time, "2020-01-01T10:00:00+02:00", utc))
        assertEquals("2023-11-14T22:13:20Z", ExportTimes.xmp(time, "2020-01-01T10:00:00Z", utc))
        assertEquals("2023-11-14T22:13:20", ExportTimes.xmp(time, "2020-01-01", utc))
    }

    @Test
    fun `unknown stored content reads as originals`() {
        assertEquals(ExportContent.ORIGINALS, ExportContent.parse(null))
        assertEquals(ExportContent.ORIGINALS, ExportContent.parse("SIDEWAYS"))
        assertEquals(ExportContent.JPEGS_ONLY, ExportContent.parse("JPEGS_ONLY"))
    }
}
