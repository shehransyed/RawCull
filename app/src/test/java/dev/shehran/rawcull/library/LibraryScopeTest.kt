package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.LibraryLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryScopeTest {
    private fun location(id: Long, opened: Long = 0L) =
        LibraryLocation(id = id, treeUri = "tree$id", displayName = "L$id", addedAt = 0L, lastOpenedAt = opened)

    @Test
    fun `descendant patterns escape what LIKE treats specially`() {
        assertEquals("2026/%", FolderQuery.descendantsPattern("2026"))
        assertEquals("""a\_b\%c\\d/%""", FolderQuery.descendantsPattern("""a_b%c\d"""))
    }

    @Test
    fun `only folders are saved between launches`() {
        assertEquals("", LibraryScope.All.savedFolder)
        assertEquals("2026", LibraryScope.Folder("2026").savedFolder)
        assertEquals("", LibraryScope.LatestImport(3).savedFolder)
    }

    @Test
    fun `an empty saved folder restores to all photos`() {
        assertEquals(LibraryScope.All, LibraryScope.fromSavedFolder(""))
        assertEquals(LibraryScope.Folder("a/b"), LibraryScope.fromSavedFolder("a/b"))
    }

    @Test
    fun `the saved location comes back with its folder and photo`() {
        val restored = LibraryRestore.resolve(listOf(location(1), location(2)), 2, "2026", "asset")

        assertEquals(2L, restored.location?.id)
        assertEquals(LibraryScope.Folder("2026"), restored.scope)
        assertEquals("asset", restored.selectedAssetId)
    }

    @Test
    fun `a removed location falls back to the most recently opened one`() {
        val restored = LibraryRestore.resolve(listOf(location(1, opened = 5), location(2, opened = 9)), 7, "2026", "asset")

        assertEquals(2L, restored.location?.id)
        assertEquals(LibraryScope.All, restored.scope)
        assertNull(restored.selectedAssetId)
    }

    @Test
    fun `with no saved location the most recently opened one opens`() {
        assertEquals(1L, LibraryRestore.resolve(listOf(location(1)), null, "", null).location?.id)
    }

    @Test
    fun `no locations restores nothing`() {
        assertNull(LibraryRestore.resolve(emptyList(), 1, "x", "y").location)
    }
}
