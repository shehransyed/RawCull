package dev.shehran.rawcull.library

/** Where the slideshow gets "now" from, so tests can use virtual time. */
fun interface SlideshowClock {
    fun nowMs(): Long
}

/** The ids a show plays, fixed when it starts (R6 D4). */
data class SlideshowPlaylist(val ids: List<String>, val startIndex: Int) {
    companion object {
        /**
         * The selection in view order if two or more photos are selected, else the whole view; starting at
         * [currentId] when it is in that list, else at the first photo. Null when there is nothing to play.
         */
        fun snapshot(viewIds: List<String>, selectedIds: Set<String>, currentId: String?): SlideshowPlaylist? {
            val ids = if (selectedIds.size >= 2) viewIds.filter { it in selectedIds } else viewIds
            if (ids.isEmpty()) return null
            return SlideshowPlaylist(ids, ids.indexOf(currentId).coerceAtLeast(0))
        }
    }
}

/**
 * The slideshow's state and clock rules (R6 D4–D6), with no Android in it. The UI tells it when the
 * photo it shows is on screen ([photoShown]) and asks how long to wait ([msUntilAdvance]); it never
 * moves on by itself, so a slow load can't use up a photo's time.
 *
 * @param isAvailable false for an id that has gone (deleted, Location removed); such photos are skipped.
 */
class Slideshow(
    private val playlist: SlideshowPlaylist,
    private val clock: SlideshowClock,
    private val isAvailable: (String) -> Boolean = { true },
    intervalMs: Long,
    loop: Boolean,
) {
    var intervalMs: Long = intervalMs
        private set
    var loop: Boolean = loop
        private set

    private val ids = playlist.ids
    private var index = playlist.startIndex.coerceIn(0, ids.lastIndex)

    /** When the current photo's dwell began, or null while it is still loading. */
    private var shownAtMs: Long? = null

    var isPaused: Boolean = false
        private set

    /** Loop is off and the last photo's time is up. */
    var hasEnded: Boolean = false
        private set

    /** The photo to show now, or null when none of the playlist is left. */
    val currentId: String?
        get() = ids.getOrNull(index)?.takeIf { !hasEnded || isAvailable(it) }

    val currentIndex: Int get() = index
    val playlistIds: List<String> get() = ids
    val size: Int get() = ids.size

    val isPlaying: Boolean get() = !isPaused && !hasEnded

    init {
        // A start photo that is already gone: begin at the next one that is there.
        if (!isAvailable(ids[index])) skipToAvailable(forward = true, from = index)
    }

    fun setInterval(ms: Long) {
        intervalMs = ms
    }

    fun setLoop(on: Boolean) {
        loop = on
    }

    /** The current photo (or its "No preview" state) is on screen: its time starts, once. */
    fun photoShown() {
        if (shownAtMs == null) shownAtMs = clock.nowMs()
    }

    /** How long until the next photo is due; null while paused, ended or still loading. */
    fun msUntilAdvance(): Long? {
        val shownAt = shownAtMs ?: return null
        if (!isPlaying) return null
        return (shownAt + intervalMs - clock.nowMs()).coerceAtLeast(0)
    }

    /**
     * Moves on if the time is up. With loop off, the last photo's time running out ends the show, paused
     * on that photo. @return true when the shown photo changed.
     */
    fun tick(): Boolean {
        if (msUntilAdvance() != 0L) return false
        return advance(userInitiated = false)
    }

    fun pause() {
        isPaused = true
    }

    /** Continues from the start of the current photo's time, so it is never shown for less than the interval. */
    fun resume() {
        if (hasEnded) return
        isPaused = false
        if (shownAtMs != null) shownAtMs = clock.nowMs()
    }

    fun togglePause() = if (isPaused) resume() else pause()

    /** → : the next photo, with a fresh interval. At the end with loop off it stays. */
    fun next(): Boolean = advance(userInitiated = true)

    /** ← : the previous photo, with a fresh interval. */
    fun previous(): Boolean {
        val target = findAvailable(forward = false, from = index) ?: return false
        if (target == index) return false
        index = target
        shownAtMs = null
        hasEnded = false
        return true
    }

    /**
     * Re-checks the playlist after photos may have gone. If the shown photo is gone, moves to the next one
     * that is there (or ends the show if none is). @return true when the shown photo changed.
     */
    fun refresh(): Boolean {
        if (isAvailable(ids[index])) return false
        val target = findAvailable(forward = true, from = index, includeWrap = loop)
        if (target == null) {
            hasEnded = true
            isPaused = true
            return false
        }
        index = target
        shownAtMs = null
        return true
    }

    /** The next [count] available photos after the current one, in play order, for prefetching. */
    fun upcoming(count: Int): List<String> {
        val result = ArrayList<String>(count)
        var at = index
        repeat(ids.size - 1) {
            if (result.size >= count) return@repeat
            at = at + 1
            if (at > ids.lastIndex) {
                if (!loop) return result
                at = 0
            }
            if (at != index && isAvailable(ids[at])) result += ids[at]
        }
        return result
    }

    /** The photo just before the current one, which the loupe keeps warm for stepping back. */
    fun behind(): String? = findAvailable(forward = false, from = index)?.takeIf { it != index }?.let { ids[it] }

    private fun advance(userInitiated: Boolean): Boolean {
        val target = findAvailable(forward = true, from = index)
        if (target == index && loop) {
            // The only photo left: it just gets another full interval.
            shownAtMs = clock.nowMs()
            return false
        }
        if (target == null || target == index) {
            // Nothing further: with loop off the show ends here, unless the viewer is only stepping.
            if (!userInitiated) {
                hasEnded = true
                isPaused = true
            }
            return false
        }
        index = target
        shownAtMs = null
        hasEnded = false
        return true
    }

    /** The next available index in a direction, wrapping only when loop is on; null if there is none. */
    private fun findAvailable(forward: Boolean, from: Int, includeWrap: Boolean = loop): Int? {
        val step = if (forward) 1 else -1
        var at = from
        repeat(ids.size) {
            at += step
            if (at > ids.lastIndex) {
                if (!includeWrap) return null
                at = 0
            } else if (at < 0) {
                if (!includeWrap) return null
                at = ids.lastIndex
            }
            if (isAvailable(ids[at])) return at
        }
        return null
    }

    private fun skipToAvailable(forward: Boolean, from: Int) {
        val target = findAvailable(forward, from, includeWrap = true)
        if (target == null) hasEnded = true else index = target
    }
}
