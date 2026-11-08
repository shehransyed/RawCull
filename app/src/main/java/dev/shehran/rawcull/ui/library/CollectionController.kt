package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.model.CollectionItem
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.repository.CollectionChange
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.library.LibraryScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the last membership change did, so the snackbar's Undo can take it back (D6). */
private sealed interface MembershipUndo {
    data class Added(val collectionId: Long, val assetIds: List<String>) : MembershipUndo
    data class Removed(val items: List<CollectionItem>) : MembershipUndo
}

/**
 * Collections in the Library (D5, D6): the pane's tree, the target collection behind B, adding and
 * removing photos, and the chips for the current photo. Culling's undo stack isn't involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CollectionController(
    private val store: LibraryStore,
    private val repository: CollectionRepository,
    private val settings: SettingsRepository,
    private val view: ViewController,
    private val setScope: (LibraryScope) -> Unit,
) {
    private var lastChange: MembershipUndo? = null

    fun start() {
        store.scope.launch {
            store.state
                .map { it.activeLocation }
                .distinctUntilChangedBy { it?.id }
                .flatMapLatest { location -> location?.let { repository.observeTree(keyOf(it)) } ?: flowOf(emptyList()) }
                .collect(::onCollections)
        }
        store.scope.launch {
            settings.targetCollectionId.collect { id -> store.update { it.copy(targetCollectionId = id) } }
        }
        store.scope.launch {
            store.state
                .map { it.selectedPhotoId }
                .distinctUntilChanged()
                .flatMapLatest { id -> id?.let(repository::observeContaining) ?: flowOf(emptyList()) }
                .collect { rows -> store.update { it.copy(photoCollections = rows) } }
        }
    }

    private fun keyOf(location: LibraryLocation) = PhotoCollection.locationKey(location)

    private fun onCollections(rows: List<PhotoCollection>) {
        store.update { it.copy(collections = rows) }
        val scope = store.value.scope
        // The collection on screen was deleted (here or by removing its set): leave it.
        val gone = scope is LibraryScope.Collection && store.value.activeLocation != null && rows.none { it.id == scope.id }
        if (gone) setScope(LibraryScope.All)
    }

    // --- THE TREE ---
    fun create(name: String, parentId: Long?, isSet: Boolean, thenAddTargets: Boolean = false) {
        val location = store.value.activeLocation ?: return
        val ids = if (thenAddTargets && !isSet) targetIds() else emptyList()
        store.update { it.copy(showAddToCollection = false) }
        store.scope.launch {
            val result = if (isSet) {
                repository.createSet(keyOf(location), name, parentId)
            } else {
                repository.createCollection(keyOf(location), name, parentId)
            }
            when (result) {
                is CollectionChange.Refused -> store.showMessage(result.message)
                is CollectionChange.Done -> {
                    parentId?.let { expand(it, open = true) }
                    if (ids.isNotEmpty()) addTo(result.id, name.trim(), ids)
                }
            }
        }
    }

    fun rename(id: Long, name: String) = change { repository.rename(id, name) }

    fun move(id: Long, newParentId: Long?) = change { repository.move(id, newParentId) }

    private fun change(block: suspend () -> CollectionChange) {
        store.scope.launch {
            (block() as? CollectionChange.Refused)?.let { store.showMessage(it.message) }
        }
    }

    /** Photos are never touched; the target and the open collection follow through [onCollections]. */
    fun delete(id: Long) {
        store.scope.launch {
            repository.delete(id)
            if (store.value.targetCollectionId == id) settings.setTargetCollection(null)
        }
    }

    fun toggleExpanded(id: Long) = expand(id, open = id !in store.value.expandedCollectionSets)

    private fun expand(id: Long, open: Boolean) {
        store.update { it.copy(expandedCollectionSets = if (open) it.expandedCollectionSets + id else it.expandedCollectionSets - id) }
    }

    fun setTarget(id: Long?) {
        val row = id?.let { wanted -> store.value.collections.firstOrNull { it.id == wanted } }
        if (id != null && (row == null || row.isSet)) return
        store.scope.launch { settings.setTargetCollection(id) }
    }

    // --- MEMBERSHIP ---
    /** The selection, else the current photo, in the order they are shown. */
    private fun targetIds(): List<String> {
        val state = store.value
        if (state.selectedPhotoIds.isNotEmpty()) return view.visibleIds.filter { it in state.selectedPhotoIds }
        return listOfNotNull(state.selectedPhotoId)
    }

    fun openAddSheet() {
        if (store.value.activeLocation == null || targetIds().isEmpty()) return
        store.update { it.copy(showAddToCollection = true) }
    }

    fun dismissAddSheet() = store.update { it.copy(showAddToCollection = false) }

    /** Adds the selection (else the current photo) to [collectionId] and offers Undo. */
    fun add(collectionId: Long) {
        val ids = targetIds()
        val name = store.value.collections.firstOrNull { it.id == collectionId && !it.isSet }?.name ?: return
        store.update { it.copy(showAddToCollection = false) }
        if (ids.isEmpty()) return
        store.scope.launch { addTo(collectionId, name, ids) }
    }

    private suspend fun addTo(collectionId: Long, name: String, ids: List<String>) {
        val added = repository.add(collectionId, ids)
        if (added.isEmpty()) return store.showMessage("Already in \"$name\".")
        lastChange = MembershipUndo.Added(collectionId, added)
        announce("Added ${photos(added.size)} to \"$name\".")
    }

    /** B: toggles the selection (else the current photo) in the target; with no target it opens the sheet. */
    fun toggleTarget() {
        val target = store.value.targetCollection ?: return openAddSheet()
        val ids = targetIds()
        if (ids.isEmpty()) return
        store.scope.launch {
            val members = repository.memberIds(target.id).toSet()
            if (ids.all { it in members }) removeFrom(target.id, target.name, ids) else addTo(target.id, target.name, ids)
        }
    }

    /** In a collection view: takes the selection (else the current photo) out of it. */
    fun removeFromActive() {
        val collection = store.value.activeCollection ?: return
        val ids = targetIds()
        if (ids.isEmpty()) return
        store.scope.launch { removeFrom(collection.id, collection.name, ids) }
    }

    private suspend fun removeFrom(collectionId: Long, name: String, ids: List<String>) {
        val removed = repository.remove(collectionId, ids)
        if (removed.isEmpty()) return
        lastChange = MembershipUndo.Removed(removed)
        announce("Removed ${photos(removed.size)} from \"$name\".")
    }

    fun undo() {
        val change = lastChange ?: return
        lastChange = null
        store.scope.launch {
            when (change) {
                is MembershipUndo.Added -> repository.remove(change.collectionId, change.assetIds)
                is MembershipUndo.Removed -> repository.restore(change.items)
            }
        }
    }

    private fun announce(message: String) {
        store.update { it.copy(snackbarMessage = message, snackbarAction = SnackbarAction.UNDO_COLLECTION) }
    }

    private fun photos(count: Int) = if (count == 1) "1 photo" else "$count photos"
}
