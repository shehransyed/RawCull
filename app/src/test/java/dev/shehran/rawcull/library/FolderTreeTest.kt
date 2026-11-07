package dev.shehran.rawcull.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {
    @Test
    fun `an empty library has no nodes`() {
        assertTrue(FolderTree.build(emptyMap()).isEmpty())
    }

    @Test
    fun `nested folders add up to subtree totals`() {
        val tree = FolderTree.build(mapOf("2026/2026-11-06" to 3, "2026/2026-11-07" to 4, "2026" to 1))

        val year = tree.single()
        assertEquals("2026", year.name)
        assertEquals("2026", year.path)
        assertEquals(1, year.ownCount)
        assertEquals(8, year.totalCount)
        assertEquals(listOf("2026/2026-11-06", "2026/2026-11-07"), year.children.map { it.path })
        assertEquals(listOf(3, 4), year.children.map { it.totalCount })
    }

    @Test
    fun `folders with photos only further down still appear`() {
        val top = FolderTree.build(mapOf("a/b/c" to 2)).single()
        assertEquals(0, top.ownCount)
        assertEquals(2, top.totalCount)
        assertEquals("a/b/c", top.children.single().children.single().path)
    }

    @Test
    fun `photos at the root have no node`() {
        assertEquals(listOf("a"), FolderTree.build(mapOf("" to 9, "a" to 1)).map { it.name })
    }

    @Test
    fun `dates and numbers sort naturally`() {
        val names = FolderTree.build(mapOf("2026-10" to 1, "2026-9" to 1, "2025-12" to 1)).map { it.name }
        assertEquals(listOf("2025-12", "2026-9", "2026-10"), names)
    }

    @Test
    fun `sorting ignores case`() {
        val names = FolderTree.build(mapOf("beta" to 1, "Alpha" to 1, "Gamma" to 1)).map { it.name }
        assertEquals(listOf("Alpha", "beta", "Gamma"), names)
    }

    @Test
    fun `leading zeros don't change the order`() {
        val names = listOf("img10", "img009", "img1").sortedWith(FolderTree.NaturalOrder)
        assertEquals(listOf("img1", "img009", "img10"), names)
    }
}
