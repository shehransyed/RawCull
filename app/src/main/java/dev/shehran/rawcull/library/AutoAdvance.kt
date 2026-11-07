package dev.shehran.rawcull.library

object AutoAdvance {
    /**
     * Where the selection goes after [editedId] was edited with advance on.
     *
     * If the edit took the photo out of the filter, the one that followed it moves into its place
     * and that is the advance: stepping again would skip a photo. At the end of the list it stays.
     */
    fun next(visibleBefore: List<String>, visibleAfter: List<String>, editedId: String): String? {
        val position = visibleAfter.indexOf(editedId)
        if (position >= 0) return visibleAfter.getOrNull(position + 1) ?: editedId

        val remaining = visibleAfter.toHashSet()
        val start = visibleBefore.indexOf(editedId)
        if (start < 0) return visibleAfter.firstOrNull()
        for (index in start + 1 until visibleBefore.size) {
            if (visibleBefore[index] in remaining) return visibleBefore[index]
        }
        for (index in start - 1 downTo 0) {
            if (visibleBefore[index] in remaining) return visibleBefore[index]
        }
        return null
    }
}
