package dev.shehran.rawcull.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictNamerTest {
    @Test
    fun `attempt zero keeps the name`() {
        assertEquals("DSC1.ARW", ConflictNamer.suffixed("DSC1.ARW", "DSC1", 0))
    }

    @Test
    fun `the suffix goes after the stem`() {
        assertEquals("DSC1-2.ARW", ConflictNamer.suffixed("DSC1.ARW", "DSC1", 2))
        assertEquals("DSC1-2.ARW.xmp", ConflictNamer.suffixed("DSC1.ARW.xmp", "DSC1", 2))
    }

    @Test
    fun `a file whose case differs from the stem keeps its own case`() {
        assertEquals("dsc1-1.jpg", ConflictNamer.suffixed("dsc1.jpg", "DSC1", 1))
    }

    @Test
    fun `dots inside the stem survive`() {
        assertEquals("trip.day1-1.ARW", ConflictNamer.suffixed("trip.day1.ARW", "trip.day1", 1))
    }

    @Test
    fun `reservations are per folder and ignore case`() {
        val namer = ConflictNamer()
        namer.reserve("2026", "DSC1.ARW")

        assertTrue(namer.isReserved("2026", "dsc1.arw"))
        assertFalse(namer.isReserved("2027", "DSC1.ARW"))
    }
}
