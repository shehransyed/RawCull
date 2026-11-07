package dev.shehran.rawcull.library

/** Culling history for one Location, kept in memory only. */
class UndoStack(private val capacity: Int = DEFAULT_CAPACITY) {
    private val done = ArrayDeque<CullChange>()
    private val undone = ArrayDeque<CullChange>()

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** Records a new edit, which ends whatever could have been redone. */
    fun push(change: CullChange) {
        done.addLast(change)
        if (done.size > capacity) done.removeFirst()
        undone.clear()
    }

    /** The change to write back to undo the latest edit, or null if there's none. */
    fun undo(): CullChange? {
        val change = done.removeLastOrNull() ?: return null
        undone.addLast(change)
        return change.inverse()
    }

    fun redo(): CullChange? {
        val change = undone.removeLastOrNull() ?: return null
        done.addLast(change)
        return change
    }

    fun clear() {
        done.clear()
        undone.clear()
    }

    /** Forgets photos that no longer exist; a step with nothing left in it goes too. */
    fun dropIds(ids: Set<String>) {
        listOf(done, undone).forEach { stack ->
            val kept = stack.mapNotNull { it.without(ids) }
            stack.clear()
            stack.addAll(kept)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
