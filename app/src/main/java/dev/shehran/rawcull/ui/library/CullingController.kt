package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.repository.PhotoCatalog
import dev.shehran.rawcull.library.AutoAdvance
import dev.shehran.rawcull.library.CullChange
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.library.CullEdits
import dev.shehran.rawcull.library.LibraryFilter
import dev.shehran.rawcull.library.UndoStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Culling edits, undo and redo, and auto-advance after an edit. */
internal class CullingController(
    private val store: LibraryStore,
    private val repository: PhotoCatalog,
    private val view: ViewController,
) {
    private val undoStack = UndoStack()

    /** Fair, so edits reach Room in the order they were made and an undo can't overtake its edit. */
    private val writeOrder = Mutex()

    val canUndo: Boolean get() = undoStack.canUndo
    val canRedo: Boolean get() = undoStack.canRedo

    /** A different Location: its edits can't be undone from here. */
    fun clearHistory() = undoStack.clear()

    /** Deleted photos can't be edited any more, so their steps leave the history. */
    fun forget(ids: Set<String>) {
        undoStack.dropIds(ids)
        publishUndoState()
    }

    /** The one way culling edits happen: on the selection if there is one, else on the current photo. */
    fun apply(edit: CullEdit, advance: Boolean = false) {
        if (!canEdit()) return
        val state = store.value
        val isBatch = state.selectedPhotoIds.isNotEmpty()
        val targets = if (isBatch) {
            state.photos.filter { it.id in state.selectedPhotoIds }
        } else {
            listOfNotNull(state.currentPhoto)
        }
        if (targets.isEmpty()) return

        val change = CullEdits.resolve(edit, targets)
        if (change != null) {
            undoStack.push(change)
            publishUndoState()
            write(change)
            if (isBatch) store.update { it.copy(snackbarMessage = change.summary, snackbarAction = SnackbarAction.UNDO) }
        }
        val tagging = edit is CullEdit.AddTag || edit is CullEdit.RemoveTag
        if (!isBatch && !tagging && (advance || state.autoAdvance)) advanceFrom(targets.single(), change)
    }

    private fun advanceFrom(photo: PhotoAsset, change: CullChange?) {
        val edited = change?.after?.get(photo.id)?.let {
            photo.copy(rating = it.rating, cullStatus = it.cullStatus, colorLabel = it.colorLabel, tags = it.tags)
        } ?: photo
        val visibleIds = view.visibleIds
        val visibleAfter = if (LibraryFilter.matches(edited, view.activeFilter)) visibleIds else visibleIds - photo.id
        AutoAdvance.next(visibleIds, visibleAfter, photo.id)?.let(view::selectPhoto)
    }

    private fun write(change: CullChange) {
        store.scope.launch { writeOrder.withLock { repository.applyChange(change) } }
    }

    private fun publishUndoState() {
        store.update { it.copy(canUndo = undoStack.canUndo, canRedo = undoStack.canRedo) }
    }

    fun undo() {
        if (!canEdit()) return
        val change = undoStack.undo() ?: return
        replay(change, "Undid")
    }

    fun redo() {
        if (!canEdit()) return
        val change = undoStack.redo() ?: return
        replay(change, "Redid")
    }

    private fun replay(change: CullChange, verb: String) {
        publishUndoState()
        write(change)
        change.ids.firstOrNull { it in view.visibleIdSet }?.let(view::selectPhoto)
        store.showMessage("$verb: ${change.summary}")
    }

    /** With the volume gone there's nowhere to write sidecars, so the catalog is read-only. */
    private fun canEdit(): Boolean {
        val available = store.value.isLocationAvailable
        if (!available) store.showMessage("Location disconnected. Reconnect it to flag or rate photos.")
        return available
    }
}
