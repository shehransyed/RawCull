package dev.shehran.rawcull.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers SafTreeWalker's skip and depth rules, which live in the pure [WalkPolicy]. */
class WalkPolicyTest {

    @Test
    fun `camera folders are entered`() {
        assertTrue(WalkPolicy.shouldDescend("DCIM", 1))
        assertTrue(WalkPolicy.shouldDescend("100MSDCF", 2))
    }

    @Test
    fun `depth is limited to six`() {
        assertTrue(WalkPolicy.shouldDescend("deep", 6))
        assertFalse(WalkPolicy.shouldDescend("deep", 7))
    }

    @Test
    fun `hidden folders are skipped`() {
        assertFalse(WalkPolicy.shouldDescend(".thumbnails", 1))
    }

    @Test
    fun `system and video folders are skipped case-insensitively`() {
        listOf(
            "MISC", "misc", "AVF_INFO", "PRIVATE", "Private", "System Volume Information", "LOST.DIR",
            "\$RECYCLE.BIN", ".Trashes", ".Spotlight-V100", ".fseventsd",
        ).forEach { assertFalse(it, WalkPolicy.shouldDescend(it, 1)) }
    }
}

class EnrichmentQueueTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    private fun EnrichmentQueue.drain(): List<String> = generateSequence { next() }.toList()

    @Test
    fun `without a focus it follows display order`() {
        assertEquals(ids, EnrichmentQueue(ids, ids.toSet()).drain())
    }

    @Test
    fun `prioritize works outward from the selection`() {
        val queue = EnrichmentQueue(ids, ids.toSet())

        queue.prioritize("c")

        assertEquals(listOf("c", "b", "d", "a", "e"), queue.drain())
    }

    @Test
    fun `already loaded assets are skipped but still anchor the focus`() {
        val queue = EnrichmentQueue(ids, setOf("a", "b", "d", "e"))

        queue.prioritize("c")

        assertEquals(listOf("b", "d", "a", "e"), queue.drain())
    }

    @Test
    fun `refocusing mid-way never repeats or loses an asset`() {
        val queue = EnrichmentQueue(ids, ids.toSet())
        val first = queue.next()

        queue.prioritize("e")

        assertEquals("a", first)
        assertEquals(listOf("e", "d", "c", "b"), queue.drain())
    }

    @Test
    fun `an unknown id changes nothing`() {
        val queue = EnrichmentQueue(ids, ids.toSet())

        queue.prioritize("missing")

        assertEquals("a", queue.next())
    }

    @Test
    fun `an empty queue returns null`() {
        assertNull(EnrichmentQueue(emptyList(), emptySet()).next())
    }

    @Test
    fun `reorder puts what is visible first and keeps what is done`() {
        val queue = EnrichmentQueue(listOf("a", "b", "c", "d"), setOf("a", "b", "c", "d"))
        assertEquals("a", queue.next())
        queue.reorder(listOf("d", "c", "a"))
        assertEquals(listOf("d", "c", "b"), queue.drain())
    }

    @Test
    fun `reorder works outward from the selection, and hidden photos wait for the visible ones`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val queue = EnrichmentQueue(ids, ids.toSet())
        queue.prioritize("d")
        queue.reorder(listOf("b", "c", "d"))
        assertEquals(listOf("d", "c", "b", "a", "e"), queue.drain())
    }

    @Test
    fun `reorder ignores ids the queue never had`() {
        val queue = EnrichmentQueue(listOf("a", "b"), setOf("a", "b"))
        queue.reorder(listOf("x", "b"))
        assertEquals(listOf("b", "a"), queue.drain())
    }
}
