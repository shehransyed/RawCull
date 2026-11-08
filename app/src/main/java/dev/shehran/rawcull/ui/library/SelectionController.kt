package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.library.GridNavigation

/** Multi-select: taps with modifiers, long-press and drag, select all. */
internal class SelectionController(
    private val store: LibraryStore,
    private val view: ViewController,
) {
    private var ctrlHeld = false
    private var shiftHeld = false
    private var selectionAnchor: String? = null
    private var dragBaseline: Set<String> = emptySet()

    /** Tracked from key events: a touch or mouse click can't say what's held on the keyboard. */
    fun onKeyboardModifiers(ctrl: Boolean, shift: Boolean) {
        ctrlHeld = ctrl
        shiftHeld = shift
    }

    /** Taps open the photo, unless a selection is under way or a modifier says otherwise. */
    fun clickPhoto(index: Int) {
        val state = store.value
        val id = state.filteredPhotos.getOrNull(index)?.id ?: return
        when {
            shiftHeld -> {
                val anchor = selectionAnchor ?: state.selectedPhotoId ?: id
                store.update { it.copy(selectedPhotoIds = it.selectedPhotoIds + GridNavigation.range(view.visibleIds, anchor, id)) }
            }
            // Reorder mode has no long press to start a selection, so a tap builds the block to move.
            ctrlHeld || state.isSelecting || state.inReorderMode -> togglePhotoSelection(id)
            else -> {
                view.selectPhoto(id)
                store.setViewMode(DamViewMode.LOUPE)
            }
        }
    }

    /** A long press: the start of a selection, which dragging then extends. */
    fun beginDragSelection(id: String) {
        if (store.value.inReorderMode) return // R7 D7: a long press there drags photos
        selectionAnchor = id
        dragBaseline = store.value.selectedPhotoIds
        store.update { it.copy(selectedPhotoIds = it.selectedPhotoIds + id) }
    }

    /** Dragging back gives photos up again, so this is always the baseline plus the current range. */
    fun extendDragSelection(id: String) {
        val anchor = selectionAnchor ?: return
        val selected = dragBaseline + GridNavigation.range(view.visibleIds, anchor, id)
        if (selected != store.value.selectedPhotoIds) store.update { it.copy(selectedPhotoIds = selected) }
    }

    fun selectAll() {
        store.update { it.copy(selectedPhotoIds = view.visibleIdSet) }
    }

    fun clearSelection() {
        selectionAnchor = null
        dragBaseline = emptySet()
        store.update { it.copy(selectedPhotoIds = emptySet()) }
    }

    fun togglePhotoSelection(id: String) {
        selectionAnchor = id
        store.update {
            it.copy(selectedPhotoIds = if (id in it.selectedPhotoIds) it.selectedPhotoIds - id else it.selectedPhotoIds + id)
        }
    }
}
