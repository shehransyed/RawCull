package dev.shehran.rawcull.ui.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.deletion.DeletionGateway
import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.preview.PreviewRepository
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.PhotoCatalog
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.repository.ViewingPrefs
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.library.SlideshowClock
import dev.shehran.rawcull.library.GridNavigation
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.library.LoupeSwipe
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns what the Library shows: the active Location and scope, filters, selection and culling.
 * A façade: the work happens in the controllers next to it, which share one [LibraryStore].
 */
class LibraryViewModel(
    @Suppress("unused") private val appContext: Context,
    repository: PhotoCatalog,
    private val settings: SettingsRepository,
    locations: LocationRepository,
    jobs: ImportJobDao,
    scanCoordinator: ScanCoordinator,
    private val writeQueue: XmpWriteQueue,
    deletions: DeletionGateway,
    previews: PreviewRepository,
    startupFinished: suspend () -> Unit,
    log: (String) -> Unit = { Log.i("RawCull.Library", it) },
    slideshowClock: SlideshowClock = SlideshowClock { SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val store = LibraryStore(viewModelScope, log)
    private val histogram = HistogramLoader(store, previews)
    private val view = ViewController(store, settings, scanCoordinator, histogram)
    private val culling = CullingController(store, repository, view)
    private val selection = SelectionController(store, view)
    private val slideshow = SlideshowController(store, view, selection, settings, slideshowClock)
    private val deletion = DeletionController(store, repository, writeQueue, deletions, scanCoordinator, culling, selection)
    private val location = LocationController(
        store, repository, settings, locations, jobs, scanCoordinator, startupFinished, view, culling, deletion, histogram,
    )

    val uiState: StateFlow<LibraryUiState> = store.state.asStateFlow()

    private var gridColumns = 1

    init {
        location.startSession()
        location.observeRows()
        view.start()
        viewModelScope.launch {
            settings.cullingPrefs.collect { prefs ->
                store.update { it.copy(autoAdvance = prefs.autoAdvance, swipeToFlag = prefs.swipeToFlag) }
            }
        }
        viewModelScope.launch {
            settings.viewingPrefs.collect { prefs -> store.update { it.copy(viewing = prefs) } }
        }
        location.observeFolders()
    }

    // --- NAVIGATION ---
    fun selectPhotoIndex(index: Int) {
        // The show decides which photo is up; jumping around would leave it showing another one.
        if (store.value.slideshow != null) return
        store.value.filteredPhotos.getOrNull(index)?.let { view.selectPhoto(it.id) }
    }

    fun nextPhoto() {
        if (store.value.slideshow != null) return slideshow.next()
        val current = store.value
        if (current.selectedIndex < current.filteredPhotos.size - 1) selectPhotoIndex(current.selectedIndex + 1)
    }

    fun previousPhoto() {
        if (store.value.slideshow != null) return slideshow.previous()
        val current = store.value
        if (current.selectedIndex > 0) selectPhotoIndex(current.selectedIndex - 1)
    }

    fun firstPhoto() = selectPhotoIndex(0)

    fun lastPhoto() = selectPhotoIndex(store.value.filteredPhotos.lastIndex)

    fun setGridColumns(columns: Int) {
        gridColumns = columns
    }

    /** Up and down mean a row in the grid and a photo everywhere else. */
    fun moveRow(delta: Int) {
        val state = store.value
        if (state.viewMode == DamViewMode.GRID) {
            selectPhotoIndex(
                GridNavigation.rowStep(state.selectedIndex, delta, gridColumns, state.sections, state.filteredPhotos.size),
            )
        } else if (delta > 0) {
            nextPhoto()
        } else {
            previousPhoto()
        }
    }

    fun onCapsLock(on: Boolean) {
        if (on != store.value.capsLock) store.update { it.copy(capsLock = on) }
    }

    fun toggleZoom() {
        // Zooming is looking closely, so a show waits for it.
        slideshow.pause()
        store.update { it.copy(zoomRequest = it.zoomRequest + 1) }
    }

    /** Esc: one step back per press, innermost first. */
    fun stepBack() {
        val state = store.value
        when {
            state.slideshow != null || state.fullWindow -> exitFullWindow()
            state.showTagDialog -> dismissTagDialog()
            state.syncDetailsPhotoId != null -> dismissSyncDetails()
            state.isSelecting -> clearSelection()
            state.viewMode != DamViewMode.GRID -> setViewMode(DamViewMode.GRID)
            state.scope != LibraryScope.All -> setScope(LibraryScope.All)
        }
    }

    /** R6 D2: the selected photo (the first one if none is) fills the window. Whatever view is underneath stays as it was. */
    fun enterFullWindow() {
        val state = store.value
        if (state.fullWindow || state.activeLocation == null || state.filteredPhotos.isEmpty()) return
        if (state.currentPhoto == null) selectPhotoIndex(0)
        store.update { it.copy(fullWindow = true) }
    }

    fun exitFullWindow() {
        slideshow.end()
        if (store.value.fullWindow) store.update { it.copy(fullWindow = false) }
    }

    /** R6 D7: focus peaking on or off, for this session only. */
    fun togglePeaking() = store.update { it.copy(peaking = !it.peaking) }

    /** R6 D9/D10: the histogram's overlay, channels and scale are saved choices. */
    fun toggleHistogramOverlay() = updateViewing { it.copy(histogramOverlay = !it.histogramOverlay) }

    fun cycleHistogramMode() = updateViewing { it.copy(histogramMode = it.histogramMode.next()) }

    fun toggleHistogramLog() = updateViewing { it.copy(histogramLog = !it.histogramLog) }

    private fun updateViewing(change: (ViewingPrefs) -> ViewingPrefs) {
        viewModelScope.launch { settings.updateViewingPrefs(change) }
    }

    fun toggleFullWindow() = if (store.value.fullWindow) exitFullWindow() else enterFullWindow()

    // --- SLIDESHOW (R6 D4-D6) ---
    fun startSlideshow() = slideshow.start()

    fun toggleSlideshow() = if (store.value.slideshow != null) exitFullWindow() else startSlideshow()

    fun endSlideshow() = exitFullWindow()

    fun toggleSlideshowPause() = slideshow.togglePause()

    fun pauseSlideshow() = slideshow.pause()

    fun onSlideshowPhotoShown(id: String) = slideshow.photoShown(id)

    fun slideshowMsUntilAdvance(): Long? = slideshow.msUntilAdvance()

    fun slideshowTick() = slideshow.tick()

    fun setSlideshowInterval(seconds: Int) = slideshow.setInterval(seconds)

    fun setSlideshowLoop(on: Boolean) = slideshow.setLoop(on)

    fun setCompareIndex(index: Int) {
        val filtered = store.value.filteredPhotos
        if (index in filtered.indices) store.update { it.copy(comparePhotoId = filtered[index].id) }
    }

    fun toggleCompareMode() {
        store.update {
            val compare = !it.isCompareMode
            it.copy(isCompareMode = compare, viewMode = if (compare) DamViewMode.COMPARE else DamViewMode.LOUPE)
        }
    }

    fun setViewMode(mode: DamViewMode) = store.setViewMode(mode)

    // --- CULLING ---
    /** The one way culling edits happen: on the selection if there is one, else on the current photo. */
    fun apply(edit: CullEdit, advance: Boolean = false) = culling.apply(edit, advance)

    fun undo() = culling.undo()

    fun redo() = culling.redo()

    fun onSwipe(swipe: LoupeSwipe) {
        when (swipe) {
            LoupeSwipe.PICK -> togglePick()
            LoupeSwipe.REJECT -> toggleReject()
            LoupeSwipe.NEXT -> nextPhoto()
            LoupeSwipe.PREVIOUS -> previousPhoto()
        }
    }

    fun togglePick() = apply(CullEdit.Flag(CullStatus.PICK))

    fun toggleReject() = apply(CullEdit.Flag(CullStatus.REJECT))

    fun setCullStatus(status: CullStatus) = apply(CullEdit.Flag(status, toggle = false))

    fun setRating(rating: Int) = apply(CullEdit.Rating(rating))

    fun setColorLabel(label: ColorLabel) {
        val current = store.value.currentPhoto?.colorLabel
        // The clear button sends NONE, which means "take the label off", not a label of its own.
        apply(CullEdit.LabelToggle(if (label == ColorLabel.NONE) current ?: label else label))
    }

    fun addTagToCurrent(tag: String) = apply(CullEdit.AddTag(tag))

    fun removeTagFromCurrent(tag: String) = apply(CullEdit.RemoveTag(tag))

    // --- FILTERS ---
    fun updateFilter(filterModifier: (FilterOptions) -> FilterOptions) = view.updateFilter(filterModifier)

    /** Ctrl+L: everything off and back on, without forgetting what was set. */
    fun toggleFilters() = view.toggleFilters()

    fun clearFilters() = updateFilter { it.cleared() }

    fun setAutoAdvance(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoAdvance(enabled) }
    }

    fun toggleSyncErrorFilter() {
        updateFilter { it.copy(syncErrorsOnly = !it.syncErrorsOnly) }
    }

    // --- LOCATIONS AND SCOPE ---
    fun selectLocation(id: Long) = location.selectLocation(id)

    fun addLocation(uri: Uri) = location.addLocation(uri.toString())

    fun relinkLocation(id: Long, uri: Uri) = location.relinkLocation(id, uri.toString())

    fun openDemoLibrary() = location.openDemoLibrary()

    fun setScope(next: LibraryScope) = location.setScope(next)

    fun showLatestImport() {
        store.value.latestImport?.let { setScope(LibraryScope.LatestImport(it.id)) }
    }

    /** "Cull in Library" after an import: opens that Location on what was just imported. */
    fun openImport(job: ImportJob) = location.openImport(job)

    fun toggleFolder(path: String) = location.toggleFolder(path)

    /** Re-checks volumes after a resume or a media broadcast; a scan isn't reading while idle. */
    fun refreshSources() = location.refreshSources()

    /** Back in the foreground: the active folder is what's worth checking for changes (D12). */
    /** The app left the screen: a show waits, and stays paused on return (R6 D6). */
    fun onAppStop() = slideshow.pause()

    fun onAppStart() {
        location.rescanActiveFolder()
    }

    fun onPullToRefresh() = location.onPullToRefresh()

    fun rescan() = location.rescan()

    fun retryLocation() = location.retryLocation()

    fun onSnackbarShown() {
        store.update { it.copy(snackbarMessage = null, snackbarAction = null) }
    }

    // --- DIALOGS ---
    fun openTagDialog() {
        store.update { it.copy(showTagDialog = true) }
    }

    fun dismissTagDialog() {
        store.update { it.copy(showTagDialog = false) }
    }

    fun showSyncDetails() {
        store.update { it.copy(syncDetailsPhotoId = it.currentPhoto?.id) }
    }

    fun dismissSyncDetails() {
        store.update { it.copy(syncDetailsPhotoId = null) }
    }

    fun retrySidecar(id: String) {
        dismissSyncDetails()
        viewModelScope.launch { writeQueue.retry(id) }
    }

    fun replaceSidecar(id: String) {
        dismissSyncDetails()
        viewModelScope.launch {
            try {
                writeQueue.replaceSidecar(id)
            } catch (e: IOException) {
                store.showMessage("Couldn't back up the old sidecar, so it wasn't replaced.")
            } catch (e: IllegalStateException) {
                store.showMessage("Couldn't back up the old sidecar, so it wasn't replaced.")
            }
        }
    }

    // --- DELETE REJECTS ---
    /** @param selectionOnly the rejects in the selection; otherwise every reject in the scope, filtered or not. */
    fun requestDeleteRejects(selectionOnly: Boolean = false) = deletion.requestDeleteRejects(selectionOnly)

    fun cancelDeletion() = deletion.cancelDeletion()

    fun confirmDeletion() = deletion.confirmDeletion()

    /** The dialog is on its way; a rotation mustn't show it again. */
    fun onTrashRequestLaunched() = deletion.onTrashRequestLaunched()

    fun onTrashResult(approved: Boolean, restoring: Boolean) = deletion.onTrashResult(approved, restoring)

    /** Files deleted permanently are gone; this brings back what went to the trash. */
    fun restoreTrashed() = deletion.restoreTrashed()

    // --- MULTI-SELECT ---
    /** Tracked from key events: a touch or mouse click can't say what's held on the keyboard. */
    fun onKeyboardModifiers(ctrl: Boolean, shift: Boolean) = selection.onKeyboardModifiers(ctrl, shift)

    /** Taps open the photo, unless a selection is under way or a modifier says otherwise. */
    fun clickPhoto(index: Int) = selection.clickPhoto(index)

    /** A long press: the start of a selection, which dragging then extends. */
    fun beginDragSelection(id: String) = selection.beginDragSelection(id)

    fun extendDragSelection(id: String) = selection.extendDragSelection(id)

    fun selectAll() = selection.selectAll()

    fun clearSelection() = selection.clearSelection()

    fun togglePhotoSelection(id: String) = selection.togglePhotoSelection(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RawCullApp
                LibraryViewModel(
                    appContext = app,
                    repository = app.container.catalogRepository,
                    settings = app.container.settingsRepository,
                    locations = app.container.locationRepository,
                    jobs = app.container.database.importJobDao(),
                    scanCoordinator = app.container.scanCoordinator,
                    writeQueue = app.container.xmpWriteQueue,
                    deletions = app.container.deletionGateway,
                    previews = app.container.previewRepository,
                    startupFinished = { app.container.startup.await() },
                )
            }
        }
    }
}
