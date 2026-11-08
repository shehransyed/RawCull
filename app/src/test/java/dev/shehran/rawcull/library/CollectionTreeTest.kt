package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionTreeTest {
    private fun c(id: Long, name: String, parent: Long? = null, set: Boolean = false) =
        PhotoCollection(id = id, locationId = 1, parentId = parent, name = name, isSet = set, createdAt = 0)

    // Weddings (set) > Smith (set) > Ceremony, Party; Weddings > Jones; top: best, Archive (set)
    private val rows = listOf(
        c(1, "Weddings", set = true),
        c(2, "Smith", parent = 1, set = true),
        c(3, "Party", parent = 2),
        c(4, "Ceremony", parent = 2),
        c(5, "Jones", parent = 1),
        c(6, "best"),
        c(7, "Archive", set = true),
    )
    private val tree = CollectionTree(rows)

    private fun names(list: List<CollectionRow>) = list.map { "  ".repeat(it.depth) + it.collection.name }

    @Test
    fun rowsAreDepthFirstWithSetsFirstThenNamesIgnoringCase() {
        assertEquals(
            listOf("Archive", "Weddings", "  Smith", "    Ceremony", "    Party", "  Jones", "best"),
            names(tree.rows()),
        )
        assertTrue(tree.rows().first { it.collection.id == 1L }.hasChildren)
        assertFalse(tree.rows().first { it.collection.id == 7L }.hasChildren)
    }

    @Test
    fun collapsedSetsHideTheirChildren() {
        assertEquals(listOf("Archive", "Weddings", "  Smith", "  Jones", "best"), names(tree.rows(expanded = setOf(1))))
        assertEquals(listOf("Archive", "Weddings", "best"), names(tree.rows(expanded = emptySet())))
    }

    @Test
    fun orphansAndRowsUnderACollectionShowAtTheTop() {
        val damaged = CollectionTree(listOf(c(1, "a", parent = 99), c(2, "b"), c(3, "c", parent = 2), c(4, "d", parent = 4)))
        assertEquals(listOf("a", "b", "c", "d"), names(damaged.rows()))
    }

    @Test
    fun aParentCycleInStoredRowsStillListsEachRowOnce() {
        val looped = CollectionTree(listOf(c(1, "a", parent = 2, set = true), c(2, "b", parent = 1, set = true), c(3, "c")))
        // Neither of the looped sets is reachable from the top, but nothing hangs or repeats.
        assertEquals(listOf("c"), names(looped.rows()))
        assertEquals(setOf(1L, 2L), looped.subtree(1))
        assertEquals(1, looped.ancestors(1).size)
    }

    @Test
    fun subtreeAndAncestors() {
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), tree.subtree(1))
        assertEquals(setOf(3L), tree.subtree(3))
        assertEquals(emptySet<Long>(), tree.subtree(42))
        assertEquals(listOf("Weddings", "Smith"), tree.ancestors(3).map { it.name })
        assertEquals(emptyList<PhotoCollection>(), tree.ancestors(6))
    }

    @Test
    fun siblingNamesClashIgnoringCaseAndSpaces() {
        assertTrue(tree.nameTaken(null, "BEST"))
        assertTrue(tree.nameTaken(2, " party "))
        assertFalse(tree.nameTaken(1, "Party"))
        assertFalse(tree.nameTaken(null, "best", exceptId = 6))
    }

    @Test
    fun moveGuardRefusesCyclesNonSetsAndClashes() {
        assertNotNull(tree.moveRefusal(1, 1))
        assertNotNull(tree.moveRefusal(1, 2))
        assertNotNull(tree.moveRefusal(5, 6))
        assertNotNull(tree.moveRefusal(5, 42))
        assertNotNull(tree.moveRefusal(42, null))
        assertNull(tree.moveRefusal(2, null))
        assertNull(tree.moveRefusal(2, 7))
        assertNull(tree.moveRefusal(3, 1))
        val clash = CollectionTree(rows + c(8, "Jones"))
        assertNotNull(clash.moveRefusal(8, 1))
    }

    @Test
    fun reorderKeepsEveryMemberExactlyOnce() {
        val current = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("c", "a", "b", "d", "e"), CollectionOrder.normalized(current, listOf("c", "a", "b", "d", "e")))
        assertEquals(listOf("e", "d", "a", "b", "c"), CollectionOrder.normalized(current, listOf("e", "d")))
        assertEquals(listOf("b", "a", "c", "d", "e"), CollectionOrder.normalized(current, listOf("b", "x", "b", "a")))
        assertEquals(current, CollectionOrder.normalized(current, emptyList()))
    }
}
