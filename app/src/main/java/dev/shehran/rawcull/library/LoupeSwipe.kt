package dev.shehran.rawcull.library

import kotlin.math.abs
import kotlin.math.sign

enum class LoupeSwipe {
    PICK,
    REJECT,
    NEXT,
    PREVIOUS,
}

object LoupeSwipes {
    /** How far across the photo a swipe has to go, as a share of its height or width. */
    const val COMMIT_FRACTION = 0.2f

    /**
     * What a finished swipe means, or null to spring back.
     *
     * @param vertical the axis the swipe locked to when it started.
     * @param distance signed travel along that axis, in pixels. Up and left are negative.
     * @param velocity signed speed along that axis, in pixels per second.
     * @param extent the photo's size along that axis, in pixels.
     * @param flingVelocity the speed that counts as a fling however short the travel.
     */
    fun resolve(
        vertical: Boolean,
        distance: Float,
        velocity: Float,
        extent: Float,
        flingVelocity: Float,
        swipeToFlag: Boolean,
    ): LoupeSwipe? {
        if (vertical && !swipeToFlag) return null
        val farEnough = abs(distance) >= extent * COMMIT_FRACTION
        // A flick backwards at the end of a drag is a change of mind, not a fling.
        val flung = abs(velocity) >= flingVelocity && sign(velocity) == sign(distance)
        if (distance == 0f || (!farEnough && !flung)) return null
        return when {
            vertical -> if (distance < 0) LoupeSwipe.PICK else LoupeSwipe.REJECT
            else -> if (distance < 0) LoupeSwipe.NEXT else LoupeSwipe.PREVIOUS
        }
    }
}
