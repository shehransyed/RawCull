package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class ImportPreviewTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun day(date: Int, hour: Int = 12) = GregorianCalendar(utc).apply { set(2026, 10, date, hour, 0, 0) }.timeInMillis

    private fun file(name: String, role: ImportItemRole, imported: Boolean = false, size: Long = 100L) = CandidateFile(
        uri = "card://$name",
        name = name,
        sizeBytes = size,
        role = role,
        format = if (role.isSidecar) null else PhotoFormat.fromFileName(name),
        alreadyImported = imported,
    )

    private fun pair(id: String, date: Long? = day(6), rawImported: Boolean = false, jpegImported: Boolean = false) = ImportCandidate(
        id = id,
        stemGroup = id,
        stem = id,
        files = listOf(
            file("$id.ARW", ImportItemRole.PRIMARY, rawImported),
            file("$id.JPG", ImportItemRole.COMPANION, jpegImported),
        ),
        captureDate = date,
    )

    private fun rawOnly(id: String) = ImportCandidate(id, id, id, listOf(file("$id.ARW", ImportItemRole.PRIMARY)), day(6))

    private fun jpegOnly(id: String) = ImportCandidate(id, id, id, listOf(file("$id.JPG", ImportItemRole.PRIMARY)), day(6))

    private val showAll = PreviewOptions(hideImported = false)

    @Test
    fun `everything new starts ticked`() {
        assertTrue(ImportPreview.isSelected(pair("A"), PreviewOptions(), emptySet()))
    }

    @Test
    fun `a photo with every image imported starts unticked`() {
        assertFalse(ImportPreview.isSelected(pair("A", rawImported = true, jpegImported = true), showAll, emptySet()))
    }

    @Test
    fun `a pair with only its RAW imported isn't imported until JPGs are off`() {
        val photo = pair("A", rawImported = true)
        assertFalse(ImportPreview.isImported(photo, showAll))
        assertTrue(ImportPreview.isImported(photo, showAll.copy(includeRaster = false)))
    }

    @Test
    fun `imported photos are hidden by default and can be shown`() {
        val photo = pair("A", rawImported = true, jpegImported = true)
        assertFalse(ImportPreview.isVisible(photo, PreviewOptions()))
        assertTrue(ImportPreview.isVisible(photo, showAll))
    }

    @Test
    fun `turning a format off hides photos that only have that format`() {
        assertFalse(ImportPreview.isVisible(jpegOnly("J"), PreviewOptions(includeRaster = false)))
        assertFalse(ImportPreview.isVisible(rawOnly("R"), PreviewOptions(includeRaw = false)))
        assertTrue(ImportPreview.isVisible(pair("A"), PreviewOptions(includeRaster = false)))
    }

    @Test
    fun `a photo found to be imported mid-scan unticks itself, unless the user decided`() {
        val before = pair("A")
        val after = pair("A", rawImported = true, jpegImported = true)

        assertTrue(ImportPreview.isSelected(before, showAll, emptySet()))
        assertFalse(ImportPreview.isSelected(after, showAll, emptySet()))

        val ticked = ImportPreview.setSelected(listOf(after), true, showAll, emptySet())
        assertTrue(ImportPreview.isSelected(after, showAll, ticked))
    }

    @Test
    fun `unticking and reticking leaves no override behind`() {
        val photo = pair("A")
        val unticked = ImportPreview.setSelected(listOf(photo), false, showAll, emptySet())
        assertEquals(setOf("A"), unticked)
        assertTrue(ImportPreview.setSelected(listOf(photo), true, showAll, unticked).isEmpty())
    }

    @Test
    fun `days are newest first, with undated last and photos in order`() {
        val early = pair("B", day(6, 9))
        val late = pair("A", day(6, 15))
        val days = ImportPreview.days(listOf(late, pair("C", day(7)), pair("D", null), early), PreviewOptions(), utc)

        assertEquals(listOf("2026-11-07", "2026-11-06", ImportPreview.UNDATED_KEY), days.map { it.key })
        assertEquals(listOf("B", "A"), days[1].candidates.map { it.id })
    }

    @Test
    fun `a day's tick reflects its photos`() {
        val a = pair("A")
        val b = pair("B")
        val day = PreviewDay("d", null, listOf(a, b))

        assertEquals(DayTick.ALL, ImportPreview.dayTick(day, showAll, emptySet()))
        assertEquals(DayTick.SOME, ImportPreview.dayTick(day, showAll, setOf("A")))
        assertEquals(DayTick.NONE, ImportPreview.dayTick(day, showAll, setOf("A", "B")))
    }

    @Test
    fun `a hidden photo is never imported, even if ticked`() {
        val imported = pair("A", rawImported = true, jpegImported = true)
        val ticked = ImportPreview.setSelected(listOf(imported), true, showAll, emptySet())

        assertEquals(setOf("A"), ImportPreview.selectedIds(listOf(imported), showAll, ticked))
        assertTrue(ImportPreview.selectedIds(listOf(imported), PreviewOptions(), ticked).isEmpty())
    }

    @Test
    fun `the summary counts ticked photos and the files the toggles allow`() {
        val photos = listOf(pair("A"), pair("B"), rawOnly("C"))

        val all = ImportPreview.summary(photos, showAll, setOf("B"))
        assertEquals(2, all.selectedPhotos)
        assertEquals(3, all.totalPhotos)
        assertEquals(3, all.files)
        assertEquals(300L, all.bytes)
        assertEquals(3, all.rawCount)
        assertEquals(2, all.rasterCount)

        val rawOnlySummary = ImportPreview.summary(photos, showAll.copy(includeRaster = false), setOf("B"))
        assertEquals(2, rawOnlySummary.files)
        assertEquals(200L, rawOnlySummary.bytes)
    }

    @Test
    fun `sidecars count towards the files`() {
        val photo = ImportCandidate("A", "A", "A", listOf(file("A.ARW", ImportItemRole.PRIMARY), file("A.xmp", ImportItemRole.ADOBE_SIDECAR, size = 5L)))
        val summary = ImportPreview.summary(listOf(photo), showAll, emptySet())
        assertEquals(2, summary.files)
        assertEquals(105L, summary.bytes)
    }

    @Test
    fun `a range covers both ends in either direction`() {
        val photos = listOf(pair("A"), pair("B"), pair("C"), pair("D"))
        assertEquals(listOf("B", "C", "D"), ImportPreview.range(photos, "B", "D").map { it.id })
        assertEquals(listOf("B", "C", "D"), ImportPreview.range(photos, "D", "B").map { it.id })
        assertTrue(ImportPreview.range(photos, "B", "gone").isEmpty())
    }

    @Test
    fun `space only blocks when it's known to be short`() {
        assertTrue(ImportPreview.isShortOfSpace(100L, 99L))
        assertFalse(ImportPreview.isShortOfSpace(100L, 100L))
        assertFalse(ImportPreview.isShortOfSpace(100L, null))
    }
}
