package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.library.CustomOrder
import dev.shehran.rawcull.library.OrderMove
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sorting, and a collection's custom order (R7 D7): each collection keeps its own sort, and reorder
 * mode moves photos in it. Moves are applied one at a time to the stored order, so a burst of key
 * repeats can't lose one; the grid shows each result at once.
 */
internal class OrderController(
    private val store: LibraryStore,
    private val repository: CollectionRepository,
    private val view: ViewController,
) {
    private val writes = Mutex()

    /** In a collection the sort is the collection's; elsewhere it's the Location's filter setting. */
    fun setSort(order: SortOrder) {
        val collection = store.value.activeCollection
        if (collection == null) {
            if (order != SortOrder.CUSTOM) view.updateFilter { it.copy(sortOrder = order) }
            return
        }
        val stored = order.name
        store.update { state ->
            state.copy(
                collections = state.collections.map { if (it.id == collection.id) it.copy(sortOrder = stored) else it },
                reordering = state.reordering && order == SortOrder.CUSTOM,
            )
        }
        store.scope.launch { repository.setSortOrder(collection.id, stored) }
    }

    fun startReorder() {
        val state = store.value
        when {
            !state.offersReorder -> Unit
            state.filtersHide -> store.showMessage("Clear filters to reorder.")
            else -> {
                store.update { it.copy(reordering = true) }
                store.setViewMode(DamViewMode.GRID)
            }
        }
    }

    fun endReorder() {
        if (store.value.reordering) store.update { it.copy(reordering = false) }
    }

    /** A drop in the grid: [pressedId], or the selection when it's part of it, in front of [beforeId] (null: the end). */
    fun drop(pressedId: String, beforeId: String?) {
        val state = store.value
        val block = if (pressedId in state.selectedPhotoIds) state.selectedPhotoIds else setOf(pressedId)
        reorder(block) { order -> CustomOrder.moveBefore(order, block, beforeId) }
    }

    /** Alt+arrows, Home and End: the selection, else the current photo. */
    fun move(move: OrderMove) {
        val state = store.value
        val block = state.selectedPhotoIds.ifEmpty { setOfNotNull(state.selectedPhotoId) }
        reorder(block) { order -> CustomOrder.move(order, block, move) }
    }

    private fun reorder(block: Set<String>, change: (List<String>) -> List<String>) {
        val state = store.value
        val collectionId = state.activeCollection?.id
        if (!state.inReorderMode || collectionId == null || block.isEmpty()) return
        store.scope.launch {
            writes.withLock {
                // The stored order, not what's on screen: an earlier move may not have come back yet.
                val full = repository.memberIds(collectionId)
                val rows = view.rows.value.associateBy { it.id }
                val shown = full.filter { it in rows }
                val next = change(shown)
                if (next == shown) return@withLock
                if (store.value.activeCollection?.id == collectionId) view.rows.value = next.mapNotNull(rows::get)
                repository.reorder(collectionId, CustomOrder.merge(full, next))
            }
        }
    }
}
