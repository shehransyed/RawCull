package dev.shehran.rawcull.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.data.repository.LocationChange
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.scan.ScanState
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.FolderNode
import dev.shehran.rawcull.library.FolderTree
import dev.shehran.rawcull.library.LibraryFilter
import dev.shehran.rawcull.library.LibraryRestore
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.SelectionLogic
import dev.shehran.rawcull.util.HistogramCalculator
import dev.shehran.rawcull.util.HistogramData
import dev.shehran.rawcull.util.RawPreviewLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class DamViewMode {
    LOUPE,
    GRID,
    COMPARE
}

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
    val isScanning: Boolean = false,
    val isRefreshing: Boolean = false,
    val scanProgress: Float? = null, // Null while the total isn't known yet
    val scanStatusText: String = "",
    val selectedPhotoIds: Set<String> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val showTagDialog: Boolean = false,
    val syncDetailsPhotoId: String? = null,
    val currentHistogram: HistogramData? = null,
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

    val comparePhoto: PhotoAsset?
        get() = filteredPhotos.getOrNull(compareIndex)

    val totalCount: Int get() = photos.size
    val picksCount: Int get() = photos.count { it.cullStatus == CullStatus.PICK }
    val rejectsCount: Int get() = photos.count { it.cullStatus == CullStatus.REJECT }
    val unflaggedCount: Int get() = photos.count { it.cullStatus == CullStatus.UNFLAGGED }
    val pairedCount: Int get() = photos.count { it.hasCompanion }
    val savingCount: Int
        get() = photos.count { it.xmpSyncStatus == XmpSyncStatus.PENDING || it.xmpSyncStatus == XmpSyncStatus.WRITING }
    val syncErrorCount: Int get() = photos.count { it.xmpSyncStatus == XmpSyncStatus.ERROR }
    val syncDetailsPhoto: PhotoAsset? get() = photos.firstOrNull { it.id == syncDetailsPhotoId }
    val isDemo: Boolean get() = activeLocation?.isDemo == true
    val rootUri: String? get() = activeLocation?.treeUri

    val scopeTitle: String
        get() = when (val active = scope) {
            LibraryScope.All -> "All photos"
            is LibraryScope.Folder -> active.relativeDir.substringAfterLast('/')
            is LibraryScope.LatestImport -> "Latest Import"
        }
}

/** Owns what the Library shows: the active Location and scope, filters, selection and culling. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val appContext: Context,
    private val repository: RawCatalogRepository,
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val jobs: ImportJobDao,
    private val scanCoordinator: ScanCoordinator,
    private val writeQueue: XmpWriteQueue,
    private val startupFinished: suspend () -> Unit,
) : ViewModel() {
    private val currentLocation = MutableStateFlow<LibraryLocation?>(null)
    private val scope = MutableStateFlow<LibraryScope>(LibraryScope.All)

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var histogramJob: Job? = null
    private var histogramKey: String? = null
    private var saveSelectionJob: Job? = null
    private var pendingSelectionId: String? = null
    private var knownLocations: List<LibraryLocation> = emptyList()

    init {
        viewModelScope.launch { restoreSession() }
        viewModelScope.launch { scanCoordinator.state.collect(::onScanState) }
        viewModelScope.launch {
            locations.observeAll().collect {
                knownLocations = it
                refreshAvailability()
                onLocationsChanged(it)
            }
        }

        viewModelScope.launch {
            combine(currentLocation, scope) { location, activeScope -> location?.treeUri to activeScope }
                .flatMapLatest { (root, activeScope) ->
                    root?.let { repository.observeScope(it, activeScope) } ?: flowOf(emptyList())
                }
                .collect(::updatePhotosList)
        }
        viewModelScope.launch {
            currentLocation
                .flatMapLatest { location ->
                    location?.let { repository.observeFolderCounts(it.treeUri) } ?: flowOf(emptyMap())
                }
                .map { counts -> FolderTree.build(counts) to counts.values.sum() }
                .flowOn(Dispatchers.Default)
                .collect { (tree, total) ->
                    _uiState.update { it.copy(folderTree = tree, locationPhotoCount = total) }
                }
        }
        viewModelScope.launch {
            currentLocation
                .flatMapLatest { location -> location?.let { jobs.observeLatestJob(it.id) } ?: flowOf(null) }
                .collect { job -> _uiState.update { it.copy(latestImport = job) } }
        }
        viewModelScope.launch {
            currentLocation
                .flatMapLatest { location -> location?.let { jobs.observeLatestJob(it.id) } ?: flowOf(null) }
                .flatMapLatest { job -> job?.let { repository.observeImportPhotoCount(it.id) } ?: flowOf(0) }
                .collect { count -> _uiState.update { it.copy(latestImportPhotoCount = count) } }
        }
    }

    private fun updatePhotosList(photoList: List<PhotoAsset>) {
        applyVisiblePhotos(photoList, _uiState.value.filterOptions)
    }

    /** Publishes a new visible list, carrying the selection over by id. */
    private fun applyVisiblePhotos(photoList: List<PhotoAsset>, filter: FilterOptions) {
        val current = _uiState.value
        val spansFolders = photoList.asSequence().map { it.relativeDir }.distinct().take(2).count() > 1
        val filtered = LibraryFilter.apply(photoList, filter, filter.sortOrder, spansFolders).rows
        val previousIds = current.filteredPhotos.map { it.id }
        val nextIds = filtered.map { it.id }
        val restored = pendingSelectionId?.takeIf { it in nextIds }
        if (nextIds.isNotEmpty()) pendingSelectionId = null
        val selectedId = restored ?: SelectionLogic.reconcile(previousIds, nextIds, current.selectedPhotoId)

        _uiState.value = current.copy(
            photos = photoList,
            filteredPhotos = filtered,
            filterOptions = filter,
            selectedPhotoId = selectedId,
            comparePhotoId = SelectionLogic.reconcileCompare(nextIds, current.comparePhotoId, selectedId),
        )
        if (selectedId != current.selectedPhotoId) onSelectionChanged()
        refreshHistogram()
    }

    private fun onSelectionChanged() {
        val id = _uiState.value.selectedPhotoId
        id?.let(scanCoordinator::prioritize)
        // Culling moves through photos quickly, and only where it stops matters on the next launch.
        saveSelectionJob?.cancel()
        saveSelectionJob = viewModelScope.launch {
            delay(SAVE_SELECTION_DELAY_MS)
            settings.setSelectedAsset(id)
        }
    }

    fun selectPhotoIndex(index: Int) {
        val filtered = _uiState.value.filteredPhotos
        if (index in filtered.indices) {
            _uiState.update { it.copy(selectedPhotoId = filtered[index].id) }
            onSelectionChanged()
            refreshHistogram()
        }
    }

    fun nextPhoto() {
        val current = _uiState.value
        if (current.selectedIndex < current.filteredPhotos.size - 1) selectPhotoIndex(current.selectedIndex + 1)
    }

    fun previousPhoto() {
        val current = _uiState.value
        if (current.selectedIndex > 0) selectPhotoIndex(current.selectedIndex - 1)
    }

    fun setCompareIndex(index: Int) {
        val filtered = _uiState.value.filteredPhotos
        if (index in filtered.indices) _uiState.update { it.copy(comparePhotoId = filtered[index].id) }
    }

    fun toggleCompareMode() {
        _uiState.update {
            val compare = !it.isCompareMode
            it.copy(isCompareMode = compare, viewMode = if (compare) DamViewMode.COMPARE else DamViewMode.LOUPE)
        }
    }

    fun setViewMode(mode: DamViewMode) {
        _uiState.update { it.copy(viewMode = mode, isCompareMode = mode == DamViewMode.COMPARE) }
    }

    // --- CULLING ---
    fun togglePick() {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newStatus = if (photo.cullStatus == CullStatus.PICK) CullStatus.UNFLAGGED else CullStatus.PICK
        viewModelScope.launch { repository.setCullStatus(photo.id, newStatus) }
    }

    fun toggleReject() {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newStatus = if (photo.cullStatus == CullStatus.REJECT) CullStatus.UNFLAGGED else CullStatus.REJECT
        viewModelScope.launch { repository.setCullStatus(photo.id, newStatus) }
    }

    fun setCullStatus(status: CullStatus) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        viewModelScope.launch { repository.setCullStatus(photo.id, status) }
    }

    fun setRating(rating: Int) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newRating = if (photo.rating == rating) 0 else rating
        viewModelScope.launch { repository.setRating(photo.id, newRating) }
    }

    fun setColorLabel(label: ColorLabel) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newLabel = if (photo.colorLabel == label) ColorLabel.NONE else label
        viewModelScope.launch { repository.setColorLabel(photo.id, newLabel) }
    }

    fun addTagToCurrent(tag: String) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val trimmed = tag.trim()
        if (trimmed.isEmpty() || photo.tags.contains(trimmed)) return
        viewModelScope.launch { repository.batchAddTag(listOf(photo.id), trimmed) }
    }

    fun removeTagFromCurrent(tag: String) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        viewModelScope.launch { repository.removeTag(photo.id, tag) }
    }

    /** With the volume gone there's nowhere to write sidecars, so the catalog is read-only. */
    private fun canEdit(): Boolean {
        val available = _uiState.value.isLocationAvailable
        if (!available) showMessage("Location disconnected. Reconnect it to flag or rate photos.")
        return available
    }

    // --- FILTERS ---
    fun updateFilter(filterModifier: (FilterOptions) -> FilterOptions) {
        applyVisiblePhotos(_uiState.value.photos, filterModifier(_uiState.value.filterOptions))
    }

    fun toggleSyncErrorFilter() {
        updateFilter { it.copy(syncErrorsOnly = !it.syncErrorsOnly) }
    }

    // --- LOCATIONS AND SCOPE ---
    private suspend fun restoreSession() {
        try {
            startupFinished()
            val session = settings.librarySession()
            val restored = LibraryRestore.resolve(
                locations.getAll(),
                session.activeLocationId,
                session.activeFolder,
                session.selectedAssetId,
            )
            restored.location?.let { location ->
                pendingSelectionId = restored.selectedAssetId
                activate(location, restored.scope)
            }
        } finally {
            _uiState.update { it.copy(isRestoring = false) }
        }
    }

    /** Shows the cached rows at once and starts the background scan (D12). */
    private suspend fun activate(location: LibraryLocation, activeScope: LibraryScope = LibraryScope.All) {
        val available = locations.isAvailable(location)
        val switching = _uiState.value.activeLocation?.id != location.id
        histogramKey = null
        _uiState.update {
            it.copy(
                activeLocation = location,
                isLocationAvailable = available,
                scope = activeScope,
                expandedFolders = emptySet(),
                photos = if (switching) emptyList() else it.photos,
                filteredPhotos = if (switching) emptyList() else it.filteredPhotos,
                currentHistogram = null,
                selectedPhotoId = if (switching) null else it.selectedPhotoId,
                comparePhotoId = if (switching) null else it.comparePhotoId,
                filterOptions = if (switching) FilterOptions() else it.filterOptions,
                selectedPhotoIds = emptySet(),
                isMultiSelectMode = false,
            )
        }
        currentLocation.value = location
        scope.value = activeScope
        locations.touch(location.id)
        if (settings.librarySession().activeLocationId != location.id) settings.setActiveLocation(location.id)
        settings.setActiveFolder(activeScope.savedFolder)
        val expanded = settings.expandedFolders(location.id)
        _uiState.update { it.copy(expandedFolders = expanded) }

        if (location.isDemo) {
            scanCoordinator.cancel()
            repository.loadSampleShoot()
        } else if (available) {
            scanCoordinator.start(location.treeUri)
        }
    }

    /** Settings can remove or relink what's on screen; the Library has to follow. */
    private suspend fun onLocationsChanged(all: List<LibraryLocation>) {
        val active = _uiState.value.activeLocation ?: return
        val current = all.firstOrNull { it.id == active.id }
        when {
            current == null -> leaveRemovedLocation(all)
            current.treeUri != active.treeUri -> activate(current)
            current.displayName != active.displayName ->
                _uiState.update { it.copy(activeLocation = current) }.also { currentLocation.value = current }
        }
    }

    private suspend fun leaveRemovedLocation(remaining: List<LibraryLocation>) {
        val next = remaining.maxByOrNull { it.lastOpenedAt }
        if (next != null) {
            activate(next)
            return
        }
        scanCoordinator.cancel()
        currentLocation.value = null
        scope.value = LibraryScope.All
        settings.setActiveLocation(null)
        _uiState.update {
            it.copy(
                activeLocation = null,
                scope = LibraryScope.All,
                photos = emptyList(),
                filteredPhotos = emptyList(),
                selectedPhotoId = null,
                comparePhotoId = null,
                currentHistogram = null,
                viewMode = DamViewMode.GRID,
            )
        }
    }

    fun selectLocation(id: Long) {
        viewModelScope.launch {
            val location = locations.get(id) ?: return@launch
            if (location.id == _uiState.value.activeLocation?.id) return@launch
            activate(location)
        }
    }

    fun addLocation(uri: Uri) {
        viewModelScope.launch {
            val location = locations.add(uri.toString())
            if (location == null) showMessage("Android didn't grant access to that folder.") else activate(location)
        }
    }

    fun relinkLocation(id: Long, uri: Uri) {
        viewModelScope.launch {
            when (val result = locations.relink(id, uri.toString())) {
                is LocationChange.Refused -> showMessage(result.message)
                LocationChange.Done -> {
                    refreshAvailability()
                    if (_uiState.value.activeLocation?.id == id) locations.get(id)?.let { activate(it) }
                }
            }
        }
    }

    fun openDemoLibrary() {
        viewModelScope.launch { activate(locations.demo()) }
    }

    fun setScope(next: LibraryScope) {
        if (next == scope.value) return
        scope.value = next
        _uiState.update { it.copy(scope = next, selectedPhotoIds = emptySet()) }
        viewModelScope.launch { settings.setActiveFolder(next.savedFolder) }
    }

    fun showLatestImport() {
        _uiState.value.latestImport?.let { setScope(LibraryScope.LatestImport(it.id)) }
    }

    /** "Cull in Library" after an import: opens that Location on what was just imported. */
    fun openImport(job: ImportJob) {
        viewModelScope.launch {
            val location = locations.get(job.locationId) ?: return@launch
            activate(location, LibraryScope.LatestImport(job.id))
            setViewMode(DamViewMode.GRID)
        }
    }

    fun toggleFolder(path: String) {
        val location = _uiState.value.activeLocation ?: return
        val expanded = _uiState.value.expandedFolders.let { if (path in it) it - path else it + path }
        _uiState.update { it.copy(expandedFolders = expanded) }
        viewModelScope.launch { settings.setExpandedFolders(location.id, expanded) }
    }

    private suspend fun refreshAvailability() {
        val rows = knownLocations.map { LocationUi(it, locations.isAvailable(it)) }
        _uiState.update { it.copy(locations = rows) }
    }

    /** Re-checks volumes after a resume or a media broadcast; a scan isn't reading while idle. */
    fun refreshSources() {
        viewModelScope.launch {
            refreshAvailability()
            val location = _uiState.value.activeLocation ?: return@launch
            val available = locations.isAvailable(location)
            if (location.id != _uiState.value.activeLocation?.id) return@launch
            val wasAvailable = _uiState.value.isLocationAvailable
            if (!available && wasAvailable) scanCoordinator.cancel()
            _uiState.update { it.copy(isLocationAvailable = available) }
            if (available && !wasAvailable && !location.isDemo) scanCoordinator.start(location.treeUri)
        }
    }

    /** Back in the foreground: the active folder is what's worth checking for changes (D12). */
    fun onAppStart() {
        rescanActiveFolder()
    }

    fun onPullToRefresh() {
        if (!rescanActiveFolder()) return
        _uiState.update { it.copy(isRefreshing = true) }
    }

    private fun rescanActiveFolder(): Boolean {
        val state = _uiState.value
        val root = state.rootUri ?: return false
        if (state.isDemo || !state.isLocationAvailable) return false
        scanCoordinator.rescanSubtree(root, state.scope.savedFolder)
        return true
    }

    fun rescan() {
        val state = _uiState.value
        val root = state.rootUri ?: return
        if (state.isDemo) return
        if (state.isLocationAvailable) scanCoordinator.start(root) else retryLocation()
    }

    fun retryLocation() {
        val location = _uiState.value.activeLocation ?: return
        viewModelScope.launch {
            if (locations.isAvailable(location)) {
                _uiState.update { it.copy(isLocationAvailable = true) }
                refreshAvailability()
                scanCoordinator.start(location.treeUri)
            } else {
                showMessage("Still not connected")
            }
        }
    }

    private fun onScanState(state: ScanState) {
        val root = currentLocation.value?.treeUri
        _uiState.update { current ->
            when (state) {
                ScanState.Idle -> current.copy(isScanning = false, isRefreshing = false, scanProgress = null, scanStatusText = "")
                is ScanState.Listing -> if (state.rootUri != root) current else current.copy(
                    isScanning = true,
                    scanProgress = null,
                    scanStatusText = "Scanning: ${state.filesSeen} files in ${state.dirsVisited} folders",
                )
                is ScanState.Enriching -> if (state.rootUri != root) current else current.copy(
                    isScanning = true,
                    isRefreshing = false,
                    scanProgress = state.done.toFloat() / state.total,
                    scanStatusText = "Reading metadata ${state.done} / ${state.total}",
                )
                is ScanState.Complete -> if (state.rootUri != root) current else current.copy(
                    isScanning = false,
                    isRefreshing = false,
                    scanProgress = null,
                    scanStatusText = "",
                )
                is ScanState.SourceUnavailable -> if (state.rootUri != root) current else current.copy(
                    isScanning = false,
                    isRefreshing = false,
                    scanProgress = null,
                    scanStatusText = "",
                    isLocationAvailable = false,
                )
            }
        }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // --- DIALOGS ---
    fun openTagDialog() {
        _uiState.update { it.copy(showTagDialog = true) }
    }

    fun dismissTagDialog() {
        _uiState.update { it.copy(showTagDialog = false) }
    }

    fun showSyncDetails() {
        _uiState.update { it.copy(syncDetailsPhotoId = it.currentPhoto?.id) }
    }

    fun dismissSyncDetails() {
        _uiState.update { it.copy(syncDetailsPhotoId = null) }
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
                showMessage("Couldn't back up the old sidecar, so it wasn't replaced.")
            } catch (e: IllegalStateException) {
                showMessage("Couldn't back up the old sidecar, so it wasn't replaced.")
            }
        }
    }

    // --- MULTI-SELECT ---
    fun toggleMultiSelectMode() {
        _uiState.update {
            val selecting = !it.isMultiSelectMode
            it.copy(isMultiSelectMode = selecting, selectedPhotoIds = if (selecting) it.selectedPhotoIds else emptySet())
        }
    }

    fun togglePhotoSelection(id: String) {
        _uiState.update {
            it.copy(selectedPhotoIds = if (id in it.selectedPhotoIds) it.selectedPhotoIds - id else it.selectedPhotoIds + id)
        }
    }

    fun batchPickSelected() = batchEdit { repository.batchSetCullStatus(it, CullStatus.PICK) }

    fun batchRejectSelected() = batchEdit { repository.batchSetCullStatus(it, CullStatus.REJECT) }

    private fun batchEdit(edit: suspend (List<String>) -> Unit) {
        if (!canEdit()) return
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch { edit(ids) }
    }

    /** Keyed on the photo's version, so enrichment batches don't restart it on every emission. */
    private fun refreshHistogram() {
        val photo = _uiState.value.currentPhoto ?: return
        if (photo.previewCacheKey == histogramKey) return
        histogramKey = photo.previewCacheKey
        histogramJob?.cancel()
        histogramJob = viewModelScope.launch {
            val bitmap = RawPreviewLoader.loadThumbnail(
                appContext,
                Uri.parse(photo.fileUri),
                photo.previewCacheKey,
                photo.format,
                maxDimension = 320,
                companionUri = photo.companionFileUri?.let { Uri.parse(it) },
            )
            if (bitmap != null) {
                val histogram = HistogramCalculator.compute(bitmap)
                _uiState.update { it.copy(currentHistogram = histogram) }
            }
        }
    }

    companion object {
        private const val SAVE_SELECTION_DELAY_MS = 500L

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
                    startupFinished = { app.container.startup.await() },
                )
            }
        }
    }
}
