package dev.shehran.rawcull.ui

/** Keeps a selection meaningful as the visible list is re-sorted, re-filtered or shrinks. */
object SelectionLogic {

    /**
     * [selectedId] if it's still in [matching]; otherwise the closest id that is, by position in
     * [previousOrder], searching forward and then backward. Null if none can be found that way.
     */
    fun nearestMatching(previousOrder: List<String>, selectedId: String?, matching: Set<String>): String? {
        if (selectedId == null) return null
        if (selectedId in matching) return selectedId
        val position = previousOrder.indexOf(selectedId)
        if (position < 0) return null
        for (index in position + 1 until previousOrder.size) {
            if (previousOrder[index] in matching) return previousOrder[index]
        }
        for (index in position - 1 downTo 0) {
            if (previousOrder[index] in matching) return previousOrder[index]
        }
        return null
    }

    /** The selection to show in [next]: never lost unless [next] is empty. */
    fun reconcile(previousOrder: List<String>, next: List<String>, selectedId: String?): String? =
        nearestMatching(previousOrder, selectedId, next.toHashSet()) ?: next.firstOrNull()

    /** The compare photo: keeps its id if possible and differs from [selectedId] when it can. */
    fun reconcileCompare(next: List<String>, compareId: String?, selectedId: String?): String? {
        if (compareId != null && compareId != selectedId && compareId in next) return compareId
        if (next.size < 2) return next.firstOrNull()
        return next[(next.indexOf(selectedId) + 1) % next.size]
    }
}
