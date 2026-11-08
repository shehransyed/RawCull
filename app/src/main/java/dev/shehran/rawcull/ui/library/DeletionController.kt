package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.deletion.DeletionGateway
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.repository.PhotoCatalog
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.DeletionPlan
import dev.shehran.rawcull.library.RejectDeletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Delete rejects: the prompt, the system's trash dialog, the permanent part, and Restore. */
internal class DeletionController(
    private val store: LibraryStore,
    private val repository: PhotoCatalog,
    private val writeQueue: XmpWriteQueue,
    private val deletions: DeletionGateway,
    private val scanCoordinator: ScanCoordinator,
    private val culling: CullingController,
    private val selection: SelectionController,
) {
    private var pendingDeletion: DeletionPlan? = null
    private var trashedFiles: List<String> = emptyList()
    private var trashedFolders: Set<String> = emptySet()

    /** A different Location: what went to the trash from the old one can't be restored from here. */
    fun forgetTrashed() {
        trashedFiles = emptyList()
        trashedFolders = emptySet()
    }

    /** @param selectionOnly the rejects in the selection; otherwise every reject in the scope, filtered or not. */
    fun requestDeleteRejects(selectionOnly: Boolean) {
        val state = store.value
        if (!state.isLocationAvailable) {
            store.showMessage("Location disconnected. Reconnect it to delete photos.")
            return
        }
        val ids = state.photos
            .filter { it.cullStatus == CullStatus.REJECT && (!selectionOnly || it.id in state.selectedPhotoIds) }
            .map { it.id }
        if (ids.isEmpty()) {
            store.showMessage("No rejected photos here")
            return
        }
        store.scope.launch {
            // Writing can create a sidecar, so the rows have to be read after this, not before.
            writeQueue.flushNow()
            val rows = repository.photosById(ids)
            val plan = if (state.isDemo) {
                RejectDeletion.rowsOnly(rows)
            } else {
                withContext(Dispatchers.IO) { RejectDeletion.plan(rows, deletions.apiLevel, deletions::mediaUri) }
            }
            store.update { it.copy(deletionPrompt = plan) }
        }
    }

    fun cancelDeletion() {
        store.update { it.copy(deletionPrompt = null) }
    }

    fun confirmDeletion() {
        val plan = store.value.deletionPrompt ?: return
        store.update { it.copy(deletionPrompt = null) }
        val trash = plan.toTrash.mapNotNull { it.mediaUri }
        if (trash.isEmpty()) {
            store.scope.launch { finishDeletion(plan) }
            return
        }
        pendingDeletion = plan
        showTrashDialog(trash, restoring = false)
    }

    private fun showTrashDialog(mediaUris: List<String>, restoring: Boolean) {
        try {
            val sender = deletions.trashRequest(mediaUris, trashed = !restoring)
            store.update { it.copy(trashRequest = TrashRequest(sender, restoring)) }
        } catch (e: RuntimeException) {
            // Nothing has happened yet, and nothing must: the permanent part only follows the trash.
            store.log("trashRequest failed: $e")
            pendingDeletion = null
            store.showMessage("Android refused the trash request, so nothing was deleted.")
        }
    }

    /** The dialog is on its way; a rotation mustn't show it again. */
    fun onTrashRequestLaunched() {
        store.update { it.copy(trashRequest = null) }
    }

    fun onTrashResult(approved: Boolean, restoring: Boolean) {
        if (restoring) {
            if (approved) onFilesRestored()
            return
        }
        val plan = pendingDeletion ?: return
        pendingDeletion = null
        if (approved) {
            store.scope.launch { finishDeletion(plan) }
        } else {
            store.showMessage("Nothing was deleted")
        }
    }

    /** Runs after the trash, if there was one: cancelling that dialog never gets this far. */
    private suspend fun finishDeletion(requested: DeletionPlan) {
        // Sidecars can't go to the trash, so they're deleted for good here. If a photo somehow didn't
        // go when the system said it did, its sidecar must stay with it.
        val notTrashed = withContext(Dispatchers.IO) {
            requested.toTrash.filter { deletions.documentExists(it.documentUri) }
        }
        val plan = requested.withoutPhotos(notTrashed.map { it.photoId }.toSet())
        val failed = withContext(Dispatchers.IO) {
            plan.toDelete.filterNot { deletions.deleteDocument(it.documentUri) }.map { it.documentUri }.toSet()
        }
        val outcome = RejectDeletion.outcome(plan, failed).let { it.copy(failedFiles = it.failedFiles + notTrashed.size) }
        val folders = store.value.photos.filter { it.id in outcome.removedIds.toHashSet() }.map { it.relativeDir }.toSet()
        repository.removeRows(outcome.removedIds)
        culling.forget(outcome.removedIds.toHashSet())
        selection.clearSelection()

        val trashed = plan.toTrash.mapNotNull { it.mediaUri }
        trashedFiles = trashed
        trashedFolders = folders
        val count = outcome.removedIds.size
        val photos = if (count == 1) "1 photo" else "$count photos"
        val done = if (trashed.isEmpty()) "$photos deleted" else "$photos moved to trash"
        val problem = when (outcome.failedFiles) {
            0 -> ""
            1 -> " · 1 file couldn't be deleted"
            else -> " · ${outcome.failedFiles} files couldn't be deleted"
        }
        store.log("deleted photos=$count trashed=${trashed.size} permanent=${plan.toDelete.size} failed=${outcome.failedFiles}")
        store.update {
            it.copy(
                snackbarMessage = done + problem,
                snackbarAction = SnackbarAction.RESTORE.takeIf { trashed.isNotEmpty() },
            )
        }
    }

    /** Files deleted permanently are gone; this brings back what went to the trash. */
    fun restoreTrashed() {
        if (trashedFiles.isNotEmpty()) showTrashDialog(trashedFiles, restoring = true)
    }

    private fun onFilesRestored() {
        val root = store.value.rootUri
        val folder = RejectDeletion.commonFolder(trashedFolders)
        forgetTrashed()
        root?.let { scanCoordinator.rescanSubtree(it, folder) }
    }
}
