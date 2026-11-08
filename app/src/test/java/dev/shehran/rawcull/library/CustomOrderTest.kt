package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomOrderTest {
    private val order = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `a non-contiguous block lands as one run in its own order`() {
        assertEquals(listOf("a", "c", "d", "b", "e", "f"), CustomOrder.moveBefore(order, setOf("e", "b"), "f"))
        assertEquals(listOf("b", "e", "a", "c", "d", "f"), CustomOrder.moveBefore(order, setOf("e", "b"), "a"))
    }

    @Test
    fun `dropping in front of a moving photo means in front of the next one that stays`() {
        // b and c are moving and d is the first that stays after b, so nothing changes.
        assertEquals(order, CustomOrder.moveBefore(order, setOf("b", "c"), "b"))
        // The last photos moving in front of themselves: there's nothing after them, so the end.
        assertEquals(order, CustomOrder.moveBefore(order, setOf("e", "f"), "e"))
    }

    @Test
    fun `null or an unknown anchor is the end`() {
        assertEquals(listOf("b", "c", "d", "e", "f", "a"), CustomOrder.moveBefore(order, setOf("a"), null))
        assertEquals(listOf("b", "c", "d", "e", "f", "a"), CustomOrder.moveBefore(order, setOf("a"), "nope"))
    }

    @Test
    fun `steps start from the block's first or last photo and gather it`() {
        assertEquals(listOf("b", "d", "a", "c", "e", "f"), CustomOrder.move(order, setOf("b", "d"), OrderMove.EARLIER))
        assertEquals(listOf("a", "c", "e", "b", "d", "f"), CustomOrder.move(order, setOf("b", "d"), OrderMove.LATER))
        assertEquals(listOf("b", "a", "c", "d", "e", "f"), CustomOrder.move(order, setOf("b"), OrderMove.EARLIER))
    }

    @Test
    fun `the keys stop at the edges`() {
        assertEquals(order, CustomOrder.move(order, setOf("a"), OrderMove.EARLIER))
        assertEquals(order, CustomOrder.move(order, setOf("a", "b"), OrderMove.START))
        assertEquals(order, CustomOrder.move(order, setOf("f"), OrderMove.LATER))
        assertEquals(order, CustomOrder.move(order, setOf("e", "f"), OrderMove.END))
        // A block split across the start still gathers there.
        assertEquals(listOf("a", "c", "b", "d", "e", "f"), CustomOrder.move(order, setOf("a", "c"), OrderMove.EARLIER))
        assertEquals(listOf("c", "e", "a", "b", "d", "f"), CustomOrder.move(order, setOf("c", "e"), OrderMove.START))
        assertEquals(listOf("a", "c", "d", "f", "b", "e"), CustomOrder.move(order, setOf("b", "e"), OrderMove.END))
    }

    @Test
    fun `nothing to move changes nothing`() {
        OrderMove.entries.forEach { assertEquals(order, CustomOrder.move(order, emptySet(), it)) }
        assertEquals(order, CustomOrder.move(order, setOf("ghost"), OrderMove.START))
    }

    @Test
    fun `merging keeps members without a row in their slots and every id once`() {
        val full = listOf("a", "x", "b", "c", "y")
        val merged = CustomOrder.merge(full, listOf("c", "a", "b"))
        assertEquals(listOf("c", "x", "a", "b", "y"), merged)
        assertEquals(full.toSet(), merged.toSet())
        assertEquals(full.size, merged.size)
    }

    @Test
    fun `custom order is a collection's default and never the Location's`() {
        assertEquals(SortOrder.CUSTOM, SortOrder.forCollection(null))
        assertEquals(SortOrder.CUSTOM, SortOrder.forCollection("SIDEWAYS"))
        assertEquals(SortOrder.NAME_ASC, SortOrder.forCollection("NAME_ASC"))
        assertEquals(SortOrder.CUSTOM, SortOrder.effective(SortOrder.NAME_ASC, SortOrder.CUSTOM))
        assertEquals(SortOrder.DATE_ASC, SortOrder.effective(SortOrder.CUSTOM, null))
        assertEquals(SortOrder.SIZE_DESC, SortOrder.effective(SortOrder.SIZE_DESC, null))
        assertEquals(false, SortOrder.CUSTOM in SortOrder.offered(inCollection = false))
        assertEquals(true, SortOrder.CUSTOM in SortOrder.offered(inCollection = true))
    }
}
