package dev.shehran.rawcull.preview

/**
 * Which photos to fetch ahead (D9), as indices into the visible order the Library filter produced.
 * What's on screen isn't included: those tiles ask for themselves, in a more urgent lane.
 */
object PrefetchWindows {
    const val GRID_ROWS = 3
    const val LOUPE_AHEAD_WIDE = 5
    const val LOUPE_AHEAD_COMPACT = 3
    const val LOUPE_BEHIND = 1

    /**
     * ±[rows] rows around the visible tiles, nearest first, the row below before the row above.
     * Section headers break rows, so near one this is a row or so off, which is harmless.
     */
    fun grid(firstVisible: Int, lastVisible: Int, columns: Int, count: Int, rows: Int = GRID_ROWS): List<Int> {
        if (count <= 0 || firstVisible < 0 || lastVisible < firstVisible) return emptyList()
        val reach = rows * columns.coerceAtLeast(1)
        val out = ArrayList<Int>(reach * 2)
        for (distance in 1..reach) {
            val below = lastVisible + distance
            val above = firstVisible - distance
            if (below < count) out += below
            if (above >= 0) out += above
        }
        return out
    }

    /**
     * The next photos in the direction of travel, then the one behind.
     *
     * @param direction +1 or -1: which way the last move went.
     */
    fun loupe(current: Int, direction: Int, count: Int, compact: Boolean): List<Int> {
        if (current !in 0 until count) return emptyList()
        val step = if (direction < 0) -1 else 1
        val ahead = if (compact) LOUPE_AHEAD_COMPACT else LOUPE_AHEAD_WIDE
        val forward = (1..ahead).map { current + it * step }
        val behind = (1..LOUPE_BEHIND).map { current - it * step }
        return (forward + behind).filter { it in 0 until count }
    }
}
