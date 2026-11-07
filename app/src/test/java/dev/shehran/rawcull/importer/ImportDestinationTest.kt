package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.StructureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class ImportDestinationTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val date = GregorianCalendar(utc).apply { set(2026, 10, 6, 12, 0, 0) }.timeInMillis
    private val standard = ImportPreset(id = 1, name = ImportPreset.STANDARD_NAME)

    private fun location(id: Long, opened: Long = 0, uri: String = "tree$id") =
        LibraryLocation(id = id, treeUri = uri, displayName = "Photos$id", addedAt = 0, lastOpenedAt = opened)

    @Test
    fun `the preview shows the location and the resolved folder`() {
        assertEquals("Photos1/2026/2026-11-06/", ImportDestination.previewPath(location(1), standard, date, utc))
    }

    @Test
    fun `flat imports show just the location`() {
        val flat = standard.copy(structureMode = StructureMode.FLAT)
        assertEquals("Photos1/", ImportDestination.previewPath(location(1), flat, date, utc))
    }

    @Test
    fun `without a location the preview says so`() {
        assertEquals("(no location)/2026/2026-11-06/", ImportDestination.previewPath(null, standard, date, utc))
    }

    @Test
    fun `the preset's own location comes first`() {
        val preset = standard.copy(locationId = 2)
        assertEquals(2L, ImportDestination.defaultLocationId(preset, listOf(location(1), location(2)), 1))
    }

    @Test
    fun `then the library's active location`() {
        assertEquals(1L, ImportDestination.defaultLocationId(standard, listOf(location(1), location(2, opened = 9)), 1))
    }

    @Test
    fun `then the most recently opened one`() {
        assertEquals(2L, ImportDestination.defaultLocationId(standard, listOf(location(1), location(2, opened = 9)), null))
    }

    @Test
    fun `the demo library is never a destination`() {
        val demo = location(3, opened = 99, uri = DEMO_ROOT_URI)
        assertNull(ImportDestination.defaultLocationId(standard, listOf(demo), 3))
    }

    @Test
    fun `presets compare by settings, not by name`() {
        val renamed = standard.copy(id = 2, name = "Other")
        assertTrue(standard.sameSettingsAs(renamed))
        assertFalse(standard.sameSettingsAs(standard.copy(projectName = "x")))
    }
}
