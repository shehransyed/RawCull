package dev.shehran.rawcull.data.scan

/**
 * Hands out pending asset ids in display order, working outward from the last prioritized asset.
 *
 * @param displayOrder every asset, so neighbours of an already-loaded selection are still found.
 * @param pending the ids that actually need enrichment.
 */
class EnrichmentQueue(
    displayOrder: List<String>,
    pending: Set<String>,
) {
    private var order = displayOrder
    private var indexById = indexOf(displayOrder)
    private var remaining = displayOrder.map { it in pending }.toBooleanArray()
    private var visibleCount = displayOrder.size
    private var focusId: String? = null
    private var above = 0
    private var below = -1
    private var takeAboveNext = true

    @Synchronized
    fun prioritize(id: String) {
        focusId = id
        val index = indexById[id] ?: return
        above = index
        below = index - 1
        takeAboveNext = true
    }

    /**
     * Puts [visible] first, in that order, keeping what's still pending. Assets the filter hides are
     * worked through afterwards, so they're ready when it changes.
     */
    @Synchronized
    fun reorder(visible: List<String>) {
        val pending = order.indices.filter { remaining[it] }.map { order[it] }.toHashSet()
        val known = indexById
        val shown = visible.filter { it in known }
        val shownSet = shown.toHashSet()
        order = shown + order.filterNot { it in shownSet }
        indexById = indexOf(order)
        remaining = order.map { it in pending }.toBooleanArray()
        visibleCount = shown.size
        above = 0
        below = -1
        takeAboveNext = true
        focusId?.let(::prioritize)
    }

    /** The next id to enrich, or null when none are left. */
    @Synchronized
    fun next(): String? {
        while (above < order.size || below >= 0) {
            // Going up stops at the end of what's visible until going down has finished.
            val aboveOpen = above < if (below >= 0) visibleCount else order.size
            val useAbove = when {
                !aboveOpen -> false
                below < 0 -> true
                else -> takeAboveNext
            }
            val index = if (useAbove) above++ else below--
            takeAboveNext = !useAbove
            if (remaining[index]) {
                remaining[index] = false
                return order[index]
            }
        }
        return null
    }

    private fun indexOf(ids: List<String>): Map<String, Int> = ids.withIndex().associate { (index, id) -> id to index }
}
