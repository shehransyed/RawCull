package dev.shehran.rawcull.library

import java.util.Locale

/**
 * @param path the node's relative dir, as stored on the catalog rows.
 * @param ownCount photos directly in this folder.
 * @param totalCount photos in this folder and everything under it.
 */
data class FolderNode(
    val name: String,
    val path: String,
    val ownCount: Int,
    val totalCount: Int,
    val children: List<FolderNode> = emptyList(),
)

object FolderTree {

    /** Builds the tree from photo counts per folder. Photos at the root belong to no folder node. */
    fun build(dirsWithCounts: Map<String, Int>): List<FolderNode> {
        val root = Builder("", "")
        for ((dir, count) in dirsWithCounts) {
            if (dir.isEmpty()) continue
            var node = root
            for (segment in dir.split('/')) {
                node = node.children.getOrPut(segment) {
                    Builder(segment, if (node.path.isEmpty()) segment else "${node.path}/$segment")
                }
            }
            node.ownCount += count
        }
        return root.childNodes()
    }

    private class Builder(val name: String, val path: String) {
        val children = LinkedHashMap<String, Builder>()
        var ownCount = 0

        fun childNodes(): List<FolderNode> = children.values
            .map { it.toNode() }
            .sortedWith(compareBy(NaturalOrder) { it.name })

        private fun toNode(): FolderNode {
            val nodes = childNodes()
            return FolderNode(name, path, ownCount, ownCount + nodes.sumOf { it.totalCount }, nodes)
        }
    }

    /** Numbers compare by value and case is ignored, so `2026-9` comes before `2026-10`. */
    object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    val endA = digitsEnd(a, i)
                    val endB = digitsEnd(b, j)
                    val numberA = a.substring(i, endA).trimStart('0')
                    val numberB = b.substring(j, endB).trimStart('0')
                    val byValue = compareValuesBy(numberA, numberB, { it.length }, { it })
                    if (byValue != 0) return byValue
                    i = endA
                    j = endB
                } else {
                    val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                    if (byChar != 0) return byChar
                    i++
                    j++
                }
            }
            val byLength = (a.length - i).compareTo(b.length - j)
            return if (byLength != 0) byLength else a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))
        }

        private fun digitsEnd(text: String, start: Int): Int {
            var end = start
            while (end < text.length && text[end].isDigit()) end++
            return end
        }
    }
}
