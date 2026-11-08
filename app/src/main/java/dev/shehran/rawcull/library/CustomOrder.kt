package dev.shehran.rawcull.library

/** Where reorder mode's keys move the selection (R7 D7). */
enum class OrderMove { EARLIER, LATER, START, END }

/**
 * Moves in a collection's custom order. A moved block keeps its relative order and lands as one
 * run, wherever its photos were. Moves name an anchor photo rather than an index, so a move that
 * arrives after another one still means what the user pointed at.
 */
object CustomOrder {
    /** [block] moved in front of [beforeId], or to the end when that's null or no photo follows it outside the block. */
    fun moveBefore(order: List<String>, block: Set<String>, beforeId: String?): List<String> {
        val anchor = beforeId?.let { id ->
            val from = order.indexOf(id)
            if (from < 0) null else order.subList(from, order.size).firstOrNull { it !in block }
        }
        val rest = order.filter { it !in block }
        return place(order, block, anchor?.let(rest::indexOf) ?: rest.size)
    }

    /** One step, or to either end. Steps start from the block's first (earlier) or last (later) photo. */
    fun move(order: List<String>, block: Set<String>, move: OrderMove): List<String> {
        val rest = order.filter { it !in block }
        val restBefore = { id: String? -> id?.let { order.indexOf(it) }?.let { at -> order.subList(0, at).count { it !in block } } }
        val at = when (move) {
            OrderMove.START -> 0
            OrderMove.END -> rest.size
            OrderMove.EARLIER -> ((restBefore(order.firstOrNull { it in block }) ?: return order) - 1).coerceAtLeast(0)
            OrderMove.LATER -> ((restBefore(order.lastOrNull { it in block }) ?: return order) + 1).coerceAtMost(rest.size)
        }
        return place(order, block, at)
    }

    private fun place(order: List<String>, block: Set<String>, at: Int): List<String> {
        val moving = order.filter { it in block }
        if (moving.isEmpty()) return order
        val rest = order.filter { it !in block }
        return rest.subList(0, at) + moving + rest.subList(at, rest.size)
    }

    /**
     * [full] (every member, rows or not) with the members of [shown] put in [shown]'s order. Members
     * with no row keep their slots, so a photo that comes back later is where it was.
     */
    fun merge(full: List<String>, shown: List<String>): List<String> {
        val shownSet = shown.toSet()
        val fullSet = full.toSet()
        val next = shown.filter { it in fullSet }.iterator()
        return full.map { if (it in shownSet && next.hasNext()) next.next() else it }
    }
}
