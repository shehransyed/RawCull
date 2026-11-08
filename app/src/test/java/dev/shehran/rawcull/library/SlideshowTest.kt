package dev.shehran.rawcull.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTest {
    private class FakeClock(var now: Long = 0) : SlideshowClock {
        override fun nowMs() = now
    }

    private val clock = FakeClock()
    private val gone = HashSet<String>()
    private val view = listOf("a", "b", "c", "d")

    private fun show(
        ids: List<String> = view,
        start: Int = 0,
        interval: Long = 5000,
        loop: Boolean = true,
    ) = Slideshow(SlideshowPlaylist(ids, start), clock, { it !in gone }, interval, loop)

    @Test
    fun `two or more selected photos play in view order, else the whole view`() {
        val selection = SlideshowPlaylist.snapshot(view, setOf("d", "b"), currentId = "d")!!
        assertEquals(listOf("b", "d"), selection.ids)
        assertEquals(1, selection.startIndex)

        assertEquals(view, SlideshowPlaylist.snapshot(view, setOf("b"), currentId = "c")!!.ids)
        assertEquals(view, SlideshowPlaylist.snapshot(view, emptySet(), currentId = "c")!!.ids)
    }

    @Test
    fun `the show starts at the current photo, or at the first when it isn't in the list`() {
        assertEquals(2, SlideshowPlaylist.snapshot(view, emptySet(), "c")!!.startIndex)
        assertEquals(0, SlideshowPlaylist.snapshot(view, setOf("b", "c"), "d")!!.startIndex)
        assertEquals(0, SlideshowPlaylist.snapshot(view, emptySet(), null)!!.startIndex)
    }

    @Test
    fun `nothing to play gives no playlist`() {
        assertNull(SlideshowPlaylist.snapshot(emptyList(), emptySet(), null))
    }

    @Test
    fun `a photo's time starts when it is shown, and it advances after the interval`() {
        val s = show(start = 1)
        assertEquals("b", s.currentId)
        assertNull(s.msUntilAdvance())

        s.photoShown()
        clock.now = 4999
        assertFalse(s.tick())
        assertEquals(1L, s.msUntilAdvance())
        clock.now = 5000
        assertTrue(s.tick())
        assertEquals("c", s.currentId)
        assertNull(s.msUntilAdvance())
    }

    @Test
    fun `a slow load doesn't shorten the dwell`() {
        val s = show()
        clock.now = 12_000 // the preview took 12 s to arrive
        assertFalse(s.tick())
        s.photoShown()
        assertEquals(5000L, s.msUntilAdvance())
        clock.now = 16_999
        assertFalse(s.tick())
        clock.now = 17_000
        assertTrue(s.tick())
    }

    @Test
    fun `photoShown counts once per photo`() {
        val s = show()
        s.photoShown()
        clock.now = 3000
        s.photoShown()
        assertEquals(2000L, s.msUntilAdvance())
    }

    @Test
    fun `stepping restarts the interval from when the next photo is shown`() {
        val s = show()
        s.photoShown()
        clock.now = 4000
        assertTrue(s.next())
        assertEquals("b", s.currentId)
        assertNull(s.msUntilAdvance())
        clock.now = 4500
        s.photoShown()
        clock.now = 9000
        assertFalse(s.tick())
        clock.now = 9500
        assertTrue(s.tick())
        assertEquals("c", s.currentId)

        assertTrue(s.previous())
        assertEquals("b", s.currentId)
    }

    @Test
    fun `loop on wraps in both directions`() {
        val s = show(start = 3)
        s.photoShown()
        clock.now = 5000
        assertTrue(s.tick())
        assertEquals("a", s.currentId)
        assertTrue(s.previous())
        assertEquals("d", s.currentId)
    }

    @Test
    fun `loop off stops paused on the last photo`() {
        val s = show(start = 3, loop = false)
        s.photoShown()
        clock.now = 5000
        assertFalse(s.tick())
        assertTrue(s.hasEnded)
        assertTrue(s.isPaused)
        assertFalse(s.isPlaying)
        assertEquals("d", s.currentId)
        assertNull(s.msUntilAdvance())

        // Stepping back leaves the end state and is still allowed; stepping on at the end isn't.
        assertTrue(s.previous())
        assertFalse(s.hasEnded)
        assertEquals("c", s.currentId)
    }

    @Test
    fun `loop off, stepping forward at the end stays and doesn't end the show`() {
        val s = show(start = 3, loop = false)
        assertFalse(s.next())
        assertFalse(s.hasEnded)
        assertTrue(s.previous())
    }

    @Test
    fun `pausing stops the clock and resuming gives the photo a full interval`() {
        val s = show()
        s.photoShown()
        clock.now = 4000
        s.pause()
        assertNull(s.msUntilAdvance())
        clock.now = 60_000
        assertFalse(s.tick())
        assertEquals("a", s.currentId)

        s.resume()
        assertEquals(5000L, s.msUntilAdvance())
        clock.now = 65_000
        assertTrue(s.tick())
    }

    @Test
    fun `pausing across a stop and start keeps the photo and stays paused`() {
        val s = show(start = 2)
        s.photoShown()
        s.pause() // the app stopped
        clock.now = 10 * 60_000 // and came back much later
        assertTrue(s.isPaused)
        assertFalse(s.tick())
        assertEquals("c", s.currentId)
    }

    @Test
    fun `pausing before the photo has loaded keeps it from starting its time`() {
        val s = show()
        s.pause()
        s.photoShown()
        clock.now = 99_000
        assertFalse(s.tick())
        s.resume()
        assertEquals(5000L, s.msUntilAdvance())
    }

    @Test
    fun `photos that disappeared are skipped`() {
        val s = show()
        gone += "b"
        s.photoShown()
        clock.now = 5000
        assertTrue(s.tick())
        assertEquals("c", s.currentId)
        assertEquals(listOf("d", "a"), s.upcoming(2))
        assertEquals("a", s.behind())
    }

    @Test
    fun `the shown photo vanishing moves on, and an empty list ends the show`() {
        val s = show(start = 1)
        s.photoShown()
        gone += "b"
        assertTrue(s.refresh())
        assertEquals("c", s.currentId)
        assertNull(s.msUntilAdvance())

        gone += setOf("a", "c", "d")
        assertFalse(s.refresh())
        assertTrue(s.hasEnded)
        assertNull(s.currentId)
    }

    @Test
    fun `a start photo that is already gone begins at the next one`() {
        gone += "a"
        assertEquals("b", show().currentId)
    }

    @Test
    fun `one photo left with loop on gets another full interval`() {
        val s = show(ids = listOf("a"))
        s.photoShown()
        clock.now = 5000
        assertFalse(s.tick())
        assertEquals(5000L, s.msUntilAdvance())
        assertEquals("a", s.currentId)
    }

    @Test
    fun `upcoming stops at the end when loop is off and wraps when it is on`() {
        assertEquals(listOf("d"), show(start = 2, loop = false).upcoming(2))
        assertEquals(listOf("d", "a"), show(start = 2).upcoming(2))
        assertEquals(listOf("b", "c", "d"), show().upcoming(10))
    }

    @Test
    fun `changing the interval applies to the next photo's time`() {
        val s = show()
        s.photoShown()
        s.setInterval(2000)
        assertEquals(2000L, s.msUntilAdvance())
    }
}
