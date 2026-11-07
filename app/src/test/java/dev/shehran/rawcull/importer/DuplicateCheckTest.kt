package dev.shehran.rawcull.importer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateCheckTest {
    private val index = setOf("hash" to 100L)
    private fun check() = DuplicateCheck(setOf(100L)) { hash, size -> (hash to size) in index }

    @Test
    fun `an unknown size is not imported and is never hashed`() = runTest {
        var hashed = false
        val check = check()

        val imported = check.isImported(55L) {
            hashed = true
            "hash"
        }

        assertFalse(imported)
        assertFalse(hashed)
        assertEquals(1, check.hashesSkipped)
        assertEquals(0, check.hashesComputed)
    }

    @Test
    fun `a known size with the same hash is imported`() = runTest {
        val check = check()
        assertTrue(check.isImported(100L) { "hash" })
        assertEquals(1, check.hashesComputed)
    }

    @Test
    fun `a known size with another hash is not imported`() = runTest {
        assertFalse(check().isImported(100L) { "other" })
    }

    @Test
    fun `an unreadable file counts as not imported`() = runTest {
        assertFalse(check().isImported(100L) { null })
    }
}
