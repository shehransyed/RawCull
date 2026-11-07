package dev.shehran.rawcull.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoupeSwipeTest {
    private fun resolve(
        vertical: Boolean,
        distance: Float,
        velocity: Float = 0f,
        swipeToFlag: Boolean = true,
    ) = LoupeSwipes.resolve(vertical, distance, velocity, extent = 1000f, flingVelocity = 2000f, swipeToFlag = swipeToFlag)

    @Test
    fun `up picks and down rejects`() {
        assertEquals(LoupeSwipe.PICK, resolve(vertical = true, distance = -250f))
        assertEquals(LoupeSwipe.REJECT, resolve(vertical = true, distance = 250f))
    }

    @Test
    fun `left goes on and right goes back`() {
        assertEquals(LoupeSwipe.NEXT, resolve(vertical = false, distance = -250f))
        assertEquals(LoupeSwipe.PREVIOUS, resolve(vertical = false, distance = 250f))
    }

    @Test
    fun `a short swipe springs back`() {
        assertNull(resolve(vertical = true, distance = -199f))
        assertEquals(LoupeSwipe.PICK, resolve(vertical = true, distance = -200f))
    }

    @Test
    fun `a fling commits however short it is`() {
        assertEquals(LoupeSwipe.REJECT, resolve(vertical = true, distance = 50f, velocity = 2500f))
    }

    @Test
    fun `a flick back the other way doesn't commit`() {
        assertNull(resolve(vertical = true, distance = 50f, velocity = -2500f))
    }

    @Test
    fun `swipe to flag off leaves only navigation`() {
        assertNull(resolve(vertical = true, distance = -500f, swipeToFlag = false))
        assertEquals(LoupeSwipe.NEXT, resolve(vertical = false, distance = -500f, swipeToFlag = false))
    }

    @Test
    fun `no travel means nothing`() {
        assertNull(resolve(vertical = true, distance = 0f, velocity = 2500f))
    }
}
