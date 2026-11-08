package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoCollection
import java.util.Locale

/** One line of the Collections pane: a collection or set, how deep it sits, whether it has children. */
data class CollectionRow(val collection: PhotoCollection, val depth: Int, val hasChildren: Boolean)

/**
 * The collections and sets of one Location as a tree (D2). A row whose parent is missing, or isn't
 * a set, sits at the top level, so a damaged catalog still shows everything. Siblings are sorted
 * sets first, then by name, ignoring case.
 */
class CollectionTree(rows: List<PhotoCollection>) {
    val byId: Map<Long, PhotoCollection> = rows.associateBy { it.id }

    private val children: Map<Long?, List<PhotoCollection>> = rows
        .groupBy { effectiveParent(it) }
        .mapValues { (_, siblings) -> siblings.sortedWith(SIBLING_ORDER) }

    /** The parent [collection] is shown under. */
    fun effectiveParent(collection: PhotoCollection): Long? =
        collection.parentId?.takeIf { it != collection.id && byId[it]?.isSet == true }

    fun childrenOf(parentId: Long?): List<PhotoCollection> = children[parentId].orEmpty()

    /** Depth-first, as the pane lists them; children of sets not in [expanded] are left out. */
    fun rows(expanded: Set<Long> = byId.keys): List<CollectionRow> {
        val out = ArrayList<CollectionRow>(byId.size)
        val seen = HashSet<Long>()
        fun visit(parentId: Long?, depth: Int) {
            for (child in childrenOf(parentId)) {
                if (!seen.add(child.id)) continue
                val kids = childrenOf(child.id)
                out += CollectionRow(child, depth, kids.isNotEmpty())
                if (child.id in expanded) visit(child.id, depth + 1)
            }
        }
        visit(null, 0)
        return out
    }

    /** [id] and everything under it. */
    fun subtree(id: Long): Set<Long> {
        val out = LinkedHashSet<Long>()
        val pending = ArrayDeque(listOf(id))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (byId.containsKey(next) && out.add(next)) childrenOf(next).forEach { pending += it.id }
        }
        return out
    }

    /** The sets from the top level down to [id]'s parent. */
    fun ancestors(id: Long): List<PhotoCollection> {
        val path = ArrayList<PhotoCollection>()
        var current = byId[id]?.let(::effectiveParent)
        while (current != null && current != id && path.none { it.id == current }) {
            val set = byId[current] ?: break
            path += set
            current = effectiveParent(set)
        }
        return path.asReversed()
    }

    /** Whether a sibling under [parentId] already has [name], ignoring case and [exceptId]. */
    fun nameTaken(parentId: Long?, name: String, exceptId: Long? = null): Boolean {
        val key = nameKey(name)
        return childrenOf(parentId).any { it.id != exceptId && nameKey(it.name) == key }
    }

    /** Why [id] can't move into [newParentId] (null: the top level), or null if it can. */
    fun moveRefusal(id: Long, newParentId: Long?): String? {
        val moving = byId[id] ?: return "That collection no longer exists."
        if (newParentId != null) {
            val target = byId[newParentId] ?: return "That set no longer exists."
            if (!target.isSet) return "Only a collection set can hold collections."
            if (newParentId in subtree(id)) return "A set can't go inside itself."
        }
        if (nameTaken(newParentId, moving.name, exceptId = id)) {
            return "There's already a \"${moving.name}\" there."
        }
        return null
    }

    companion object {
        fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

        private val SIBLING_ORDER = compareByDescending<PhotoCollection> { it.isSet }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.id }
    }
}

/** Custom order of a collection's members. */
object CollectionOrder {
    /**
     * [requested] as the new order, with every member of [current] exactly once: ids that aren't
     * members and repeats are dropped, and members [requested] leaves out keep their relative order
     * after it.
     */
    fun normalized(current: List<String>, requested: List<String>): List<String> {
        val members = current.toSet()
        val out = LinkedHashSet<String>(current.size)
        requested.filterTo(out) { it in members }
        out += current
        return out.toList()
    }
}
