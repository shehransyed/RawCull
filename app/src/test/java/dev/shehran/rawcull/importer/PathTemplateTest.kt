package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.StructureMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class PathTemplateTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val date = GregorianCalendar(utc).apply { set(2026, 10, 6, 12, 0, 0) }.timeInMillis

    private fun resolve(
        mode: StructureMode,
        datePattern: String = ImportPreset.DEFAULT_DATE_PATTERN,
        custom: String = "",
        project: String = "",
        captureDate: Long? = date,
    ) = PathTemplate.resolve(mode, datePattern, custom, project, captureDate, utc)

    @Test
    fun `each date format resolves`() {
        val expected = listOf("2026/2026-11-06", "2026/11/06", "2026-11-06", "2026/2026-11")
        assertEquals(expected, ImportPreset.DATE_PATTERNS.map { resolve(StructureMode.DATE, datePattern = it) })
    }

    @Test
    fun `month and day are zero padded`() {
        val january = GregorianCalendar(utc).apply { set(2027, 0, 3, 12, 0, 0) }.timeInMillis
        assertEquals("2027/2027-01-03", resolve(StructureMode.DATE, captureDate = january))
    }

    @Test
    fun `the date is read in the given time zone`() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val lateUtc = GregorianCalendar(utc).apply { set(2026, 10, 6, 20, 0, 0) }.timeInMillis
        val resolved = PathTemplate.resolve(StructureMode.DATE, "{year}-{month}-{day}", "", "", lateUtc, tokyo)
        assertEquals("2026-11-07", resolved)
    }

    @Test
    fun `custom pattern mixes text and tokens`() {
        assertEquals("Trips/2026/Nov 11", resolve(StructureMode.CUSTOM, custom = "Trips/{year}/Nov {month}"))
    }

    @Test
    fun `custom pattern without tokens needs no date`() {
        assertEquals("Scans/Family", resolve(StructureMode.CUSTOM, custom = "Scans/Family", captureDate = null))
    }

    @Test
    fun `project folder is one segment and ignores the date`() {
        assertEquals("Smith_Jones wedding", resolve(StructureMode.PROJECT, project = "Smith/Jones wedding", captureDate = null))
    }

    @Test
    fun `flat is the location root`() {
        assertEquals("", resolve(StructureMode.FLAT))
    }

    @Test
    fun `a missing date goes to Undated`() {
        assertEquals("Undated", resolve(StructureMode.DATE, captureDate = null))
        assertEquals("Undated", resolve(StructureMode.CUSTOM, custom = "Trips/{year}", captureDate = null))
    }

    @Test
    fun `characters providers reject are replaced`() {
        assertEquals("a_b_c_d_e_f_g_h", PathTemplate.sanitize("""a\b:c*d?e"f<g>h"""))
        assertEquals("a_b", PathTemplate.sanitize("a|b"))
    }

    @Test
    fun `dots and spaces are trimmed from each segment`() {
        assertEquals("name/inner", PathTemplate.sanitize(" name. /  ..inner.. "))
    }

    @Test
    fun `empty segments collapse and slashes never lead or trail`() {
        assertEquals("a/b", PathTemplate.sanitize("/a//b/"))
    }

    @Test
    fun `parent references can't escape the location`() {
        assertEquals("a/b", PathTemplate.sanitize("../a/../b/.."))
    }
}
