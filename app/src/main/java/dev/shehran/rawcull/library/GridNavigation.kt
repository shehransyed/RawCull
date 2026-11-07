package dev.shehran.rawcull.library

object GridNavigation {
    /** Position in the grid of the photo at [index]: each section before it adds a header. */
    fun gridItemIndex(index: Int, sections: List<FolderSection>): Int =
        index + sections.count { it.firstIndex <= index }

    /** Everything from [from] to [to], whichever comes first; just [to] if [from] has gone. */
    fun range(ids: List<String>, from: String, to: String): Set<String> {
        val start = ids.indexOf(from)
        val end = ids.indexOf(to)
        if (end < 0) return emptySet()
        if (start < 0) return setOf(to)
        return ids.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
    }

    /**
     * The index one row above or below [index] in a grid of [columns], keeping the column where it
     * can. Each section starts a new row, so positions are worked out inside the section.
     *
     * @param delta +1 for down, -1 for up.
     * @return [index] itself when there's no row that way.
     */
    fun rowStep(index: Int, delta: Int, columns: Int, sections: List<FolderSection>, size: Int): Int {
        if (index !in 0 until size || columns < 1) return index
        val all = sections.ifEmpty { listOf(FolderSection("", 0, size)) }
        val at = all.indexOfFirst { index >= it.firstIndex && index < it.firstIndex + it.count }
        if (at < 0) return index
        val section = all[at]
        val position = index - section.firstIndex
        val column = position % columns
        val row = position / columns
        val lastRow = (section.count - 1) / columns

        return if (delta > 0) {
            when {
                row < lastRow -> section.firstIndex + minOf(position + columns, section.count - 1)
                at < all.lastIndex -> all[at + 1].let { it.firstIndex + minOf(column, it.count - 1) }
                else -> index
            }
        } else {
            when {
                row > 0 -> section.firstIndex + position - columns
                at > 0 -> all[at - 1].let { previous ->
                    val previousLastRow = (previous.count - 1) / columns
                    previous.firstIndex + minOf(previousLastRow * columns + column, previous.count - 1)
                }
                else -> index
            }
        }
    }
}
