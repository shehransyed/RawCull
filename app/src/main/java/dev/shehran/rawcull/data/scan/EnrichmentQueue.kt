package dev.shehran.rawcull.data.scan

/**
 * Hands out pending asset ids in display order, working outward from the last prioritized asset.
 *
 * @param displayOrder every asset, so neighbours of an already-loaded selection are still found.
 * @param pending the ids that actually need enrichment.
 */
class EnrichmentQueue(
    private val displayOrder: List<String>,
    pending: Set<String>,
) {
    private val indexById = displayOrder.withIndex().associate { (index, id) -> id to index }
    private val remaining = displayOrder.map { it in pending }.toBooleanArray()
    private var above = 0
    private var below = -1
    private var takeAboveNext = true

    @Synchronized
    fun prioritize(id: String) {
        val index = indexById[id] ?: return
        above = index
        below = index - 1
        takeAboveNext = true
    }

    /** The next id to enrich, or null when none are left. */
    @Synchronized
    fun next(): String? {
        while (above < displayOrder.size || below >= 0) {
            val useAbove = when {
                above >= displayOrder.size -> false
                below < 0 -> true
                else -> takeAboveNext
            }
            val index = if (useAbove) above++ else below--
            takeAboveNext = !useAbove
            if (remaining[index]) {
                remaining[index] = false
                return displayOrder[index]
            }
        }
        return null
    }
}
