package dev.shehran.rawcull.ui.library

import android.content.IntentSender
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.repository.ViewingPrefs
import dev.shehran.rawcull.library.DeletionPlan
import dev.shehran.rawcull.library.FilterCounts
import dev.shehran.rawcull.library.FolderNode
import dev.shehran.rawcull.library.FolderSection
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.util.HistogramData

enum class DamViewMode {
    LOUPE,
    GRID,
    COMPARE
}

/** A running slideshow, as the UI needs it (R6 D4-D6). The clock itself is in [SlideshowController]. */
data class SlideshowUi(
    val playlist: List<String>,
    val currentId: String?,
    val position: Int,
    val isPaused: Boolean,
    val hasEnded: Boolean,
    /** Bumped on resume, so the loupe refits after a pinch paused the show. */
    val refit: Int,
    /** Bumped on every change, which restarts the UI's wait for the next photo. */
    val version: Int,
)

enum class SnackbarAction(val label: String) {
    UNDO("Undo"),
    UNDO_COLLECTION("Undo"), // A collection membership change, which isn't on the culling stack (D6)
    RESTORE("Restore"),
}

/** The system's trash dialog, and whether it's bringing files back. */
class TrashRequest(val sender: IntentSender, val restoring: Boolean)

/** A Location plus whether its volume is connected right now. */
data class LocationUi(val location: LibraryLocation, val isAvailable: Boolean)

data class LibraryUiState(
    val photos: List<PhotoAsset> = emptyList(),
    val filteredPhotos: List<PhotoAsset> = emptyList(),
    val selectedPhotoId: String? = null,
    val comparePhotoId: String? = null,
    val isCompareMode: Boolean = false,
    val viewMode: DamViewMode = DamViewMode.GRID,
    val filterOptions: FilterOptions = FilterOptions(),
    val locations: List<LocationUi> = emptyList(),
    val activeLocation: LibraryLocation? = null,
    val isLocationAvailable: Boolean = true,
    val isRestoring: Boolean = true, // True until the launch-time check has finished
    val scope: LibraryScope = LibraryScope.All,
    val folderTree: List<FolderNode> = emptyList(),
    val locationPhotoCount: Int = 0,
    val expandedFolders: Set<String> = emptySet(),
    val latestImport: ImportJob? = null,
    val latestImportPhotoCount: Int = 0,
    val snackbarMessage: String? = null,
    val snackbarAction: SnackbarAction? = null,
    val deletionPrompt: DeletionPlan? = null,
    val trashRequest: TrashRequest? = null, // A system dialog waiting to be shown
    val isScanning: Boolean = false,
    val isRefreshing: Boolean = false,
    val scanProgress: Float? = null, // Null while the total isn't known yet
    val scanStatusText: String = "",
    val selectedPhotoIds: Set<String> = emptySet(),
    val showTagDialog: Boolean = false,
    val syncDetailsPhotoId: String? = null,
    val currentHistogram: HistogramData? = null,
    val sections: List<FolderSection> = emptyList(),
    val counts: FilterCounts = FilterCounts(),
    val filtersEnabled: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val autoAdvance: Boolean = false,
    val swipeToFlag: Boolean = true,
    val capsLock: Boolean = false,
    val slideshow: SlideshowUi? = null,
    val viewing: ViewingPrefs = ViewingPrefs(),
    val peaking: Boolean = false, // R6 D7: per session, starts off
    val fullWindow: Boolean = false, // R6 D2: the photo owns the whole window, system bars hidden
    val collections: List<PhotoCollection> = emptyList(), // The active Location's collections and sets
    val expandedCollectionSets: Set<Long> = emptySet(),
    val targetCollectionId: Long? = null, // What B adds to (D6)
    val photoCollections: List<PhotoCollection> = emptyList(), // The current photo's collections: inspector chips
    val showAddToCollection: Boolean = false,
    val reordering: Boolean = false, // R7 D7: asked for; it only holds while [canReorder] does, see [inReorderMode]
    val zoomRequest: Int = 0, // Bumped by Z and Space; the loupe on screen toggles its zoom
) {
    /** Derived, not stored: enrichment re-sorts the list, and the selection must follow the photo. */
    val selectedIndex: Int by lazy(LazyThreadSafetyMode.NONE) {
        filteredPhotos.indexOfFirst { it.id == selectedPhotoId }
    }

    val compareIndex: Int by lazy(LazyThreadSafetyMode.NONE) {
        filteredPhotos.indexOfFirst { it.id == comparePhotoId }
    }

    val currentPhoto: PhotoAsset?
        get() = filteredPhotos.getOrNull(selectedIndex)

    /** What culling edits and the full window shows: the slideshow's photo while one plays, else the current one. */
    val shownPhoto: PhotoAsset?
        get() = slideshow?.currentId?.let { id -> photos.firstOrNull { it.id == id } } ?: currentPhoto

    val comparePhoto: PhotoAsset?
        get() = filteredPhotos.getOrNull(compareIndex)

    val totalCount: Int get() = photos.size
    val picksCount: Int get() = counts.flags[CullStatus.PICK] ?: 0
    val rejectsCount: Int get() = counts.flags[CullStatus.REJECT] ?: 0
    val unflaggedCount: Int get() = counts.flags[CullStatus.UNFLAGGED] ?: 0
    val pairedCount: Int get() = counts.paired
    val savingCount: Int get() = counts.saving
    val syncErrorCount: Int get() = counts.syncErrors
    /** What the tag dialog lists: the current photo's tags, or every tag across the selection. */
    val tagDialogTags: List<String>
        get() = if (isSelecting) {
            photos.filter { it.id in selectedPhotoIds }.flatMap { it.tags }.distinct()
        } else {
            currentPhoto?.tags.orEmpty()
        }
    val syncDetailsPhoto: PhotoAsset? get() = photos.firstOrNull { it.id == syncDetailsPhotoId }
    val isDemo: Boolean get() = activeLocation?.isDemo == true
    val isSelecting: Boolean get() = selectedPhotoIds.isNotEmpty()

    /** Caps Lock counts as well as the toggle, as in Lightroom. */
    val advancesAfterEdits: Boolean get() = autoAdvance || capsLock
    val rootUri: String? get() = activeLocation?.treeUri

    val scopeTitle: String
        get() = when (val active = scope) {
            LibraryScope.All -> "All photos"
            is LibraryScope.Folder -> active.relativeDir.substringAfterLast('/')
            is LibraryScope.LatestImport -> "Latest Import"
            is LibraryScope.Collection -> collections.firstOrNull { it.id == active.id }?.name ?: "Collection"
        }

    /** The collection on screen, if the scope is one. */
    val activeCollection: PhotoCollection?
        get() = (scope as? LibraryScope.Collection)?.let { s -> collections.firstOrNull { it.id == s.id } }

    /** B's target, if it still exists in this Location and can hold photos. */
    val targetCollection: PhotoCollection?
        get() = collections.firstOrNull { it.id == targetCollectionId && !it.isSet }

    /** A collection's own sort when one is open (D7), else null. */
    val collectionSort: SortOrder?
        get() = activeCollection?.let { SortOrder.forCollection(it.sortOrder) }

    /** The sort on screen. */
    val sortOrder: SortOrder get() = SortOrder.effective(filterOptions.sortOrder, collectionSort)

    /** Whether filters hide anything right now (Ctrl+L can turn them off without forgetting them). */
    val filtersHide: Boolean get() = filtersEnabled && filterOptions.isActive

    /** Whether Reorder is offered: a collection in custom order. It's refused while [filtersHide]. */
    val offersReorder: Boolean get() = collectionSort == SortOrder.CUSTOM

    val canReorder: Boolean get() = offersReorder && !filtersHide

    val inReorderMode: Boolean get() = reordering && canReorder
}
