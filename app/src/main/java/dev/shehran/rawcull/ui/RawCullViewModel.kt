package dev.shehran.rawcull.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullFilterMode
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.ExportConfig
import dev.shehran.rawcull.data.model.ExportMode
import dev.shehran.rawcull.data.model.ExportProgress
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.RecentSource
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.repository.StoredFolder
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.scan.ScanState
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.LibraryRestore
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.util.HistogramCalculator
import dev.shehran.rawcull.util.HistogramData
import dev.shehran.rawcull.util.RawPreviewLoader
import dev.shehran.rawcull.util.SafPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class DamViewMode {
    LOUPE,
    GRID,
    COMPARE
}

/** A recent source plus whether its volume is connected right now. */
data class RecentSourceUi(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long,
    val isAvailable: Boolean,
)

data class DamUiState(
    val photos: List<PhotoAsset> = emptyList(),
    val filteredPhotos: List<PhotoAsset> = emptyList(),
    val selectedPhotoId: String? = null,
    val comparePhotoId: String? = null,
    val isCompareMode: Boolean = false,
    val viewMode: DamViewMode = DamViewMode.LOUPE,
    val filterOptions: FilterOptions = FilterOptions(),
    val sourceFolderName: String = "No source",
    val currentRootUri: String? = null,
    val activeLocationId: Long? = null,
    val scope: LibraryScope = LibraryScope.All,
    val isSourceAvailable: Boolean = true,
    val isRestoring: Boolean = true, // True until the launch-time source check has finished
    val recentSources: List<RecentSourceUi> = emptyList(),
    val storageEpoch: Int = 0, // Bumped whenever volumes may have changed
    val snackbarMessage: String? = null,
    val destinationFolderName: String = "Not set",
    val destinationFolderUri: String? = null,
    val isScanning: Boolean = false,
    val scanProgress: Float? = null, // Null while the total isn't known yet
    val scanStatusText: String = "",
    val selectedPhotoIds: Set<String> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val showExportDialog: Boolean = false,
    val showTagDialog: Boolean = false,
    val showSettings: Boolean = false,
    val syncDetailsPhotoId: String? = null,
    val rejectAsNegativeRating: Boolean = true,
    val exportProgress: ExportProgress = ExportProgress(),
    val currentHistogram: HistogramData? = null,
    val isLoupeZoomed: Boolean = false,
    val zoomFactor: Float = 1f
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
    val isDemoSource: Boolean get() = currentRootUri == DEMO_ROOT_URI
}

@OptIn(ExperimentalCoroutinesApi::class)
class RawCullViewModel(
    private val appContext: Context,
    private val repository: RawCatalogRepository,
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val scanCoordinator: ScanCoordinator,
    private val writeQueue: XmpWriteQueue,
) : ViewModel() {
    private val currentRootUri = MutableStateFlow<String?>(null)
    private val scope = MutableStateFlow<LibraryScope>(LibraryScope.All)
    private var pendingSelectionId: String? = null
    private var saveSelectionJob: Job? = null

    private val _uiState = MutableStateFlow(DamUiState())
    val uiState: StateFlow<DamUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null
    private var histogramJob: Job? = null
    private var histogramKey: String? = null
    private var recents: List<RecentSource> = emptyList()

    init {
        viewModelScope.launch { restoreSession() }
        viewModelScope.launch {
            settings.recentSources.collect {
                recents = it
                refreshRecentAvailability()
            }
        }
        viewModelScope.launch { scanCoordinator.state.collect(::onScanState) }
        viewModelScope.launch {
            settings.xmpPrefs.collect { prefs ->
                _uiState.update { it.copy(rejectAsNegativeRating = prefs.rejectAsNegativeRating) }
            }
        }

        viewModelScope.launch {
            combine(currentRootUri, scope) { root, activeScope -> root to activeScope }
                .flatMapLatest { (root, activeScope) ->
                    root?.let { repository.observeScope(it, activeScope) } ?: flowOf(emptyList())
                }
                .collect(::updatePhotosList)
        }
    }

    private fun updatePhotosList(photoList: List<PhotoAsset>) {
        val current = _uiState.value
        applyVisiblePhotos(photoList, current.filterOptions)
    }

    /** Publishes a new visible list, carrying the selection over by id. */
    private fun applyVisiblePhotos(photoList: List<PhotoAsset>, filter: FilterOptions) {
        val current = _uiState.value
        val filtered = applyFilterAndSort(photoList, filter)
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

    private fun applyFilterAndSort(
        list: List<PhotoAsset>,
        filter: FilterOptions
    ): List<PhotoAsset> {
        var filtered = list.filter { photo ->
            // Cull mode filter
            val cullMatch = when (filter.cullFilter) {
                CullFilterMode.ALL -> true
                CullFilterMode.PICKS_ONLY -> photo.cullStatus == CullStatus.PICK
                CullFilterMode.UNFLAGGED_ONLY -> photo.cullStatus == CullStatus.UNFLAGGED
                CullFilterMode.REJECTS_ONLY -> photo.cullStatus == CullStatus.REJECT
                CullFilterMode.PICKS_AND_UNFLAGGED -> photo.cullStatus != CullStatus.REJECT
            }

            // Rating filter
            val ratingMatch = if (filter.exactRating != null) {
                photo.rating == filter.exactRating
            } else {
                photo.rating >= filter.minRating
            }

            // Color label filter
            val colorMatch = filter.colorFilter == null || photo.colorLabel == filter.colorFilter

            // Format filter
            val formatMatch = filter.formatFilter == null || photo.format == filter.formatFilter

            // Pair mode filter (RAW+JPG vs RAW only)
            val pairMatch = filter.pairFilter == null || photo.pairMode == filter.pairFilter

            // Tag filter
            val tagMatch = filter.tagFilter.isNullOrBlank() || photo.tags.contains(filter.tagFilter)

            // Search query
            val searchMatch = filter.searchQuery.isBlank() ||
                    photo.fileName.contains(filter.searchQuery, ignoreCase = true) ||
                    (photo.cameraModel?.contains(filter.searchQuery, ignoreCase = true) == true) ||
                    (photo.lensModel?.contains(filter.searchQuery, ignoreCase = true) == true) ||
                    photo.tags.any { it.contains(filter.searchQuery, ignoreCase = true) }

            val syncMatch = !filter.syncErrorsOnly || photo.xmpSyncStatus == XmpSyncStatus.ERROR

            cullMatch && ratingMatch && colorMatch && formatMatch && pairMatch && tagMatch && searchMatch && syncMatch
        }

        // Apply sort
        filtered = when (filter.sortOrder) {
            SortOrder.DATE_ASC -> filtered.sortedBy { it.dateTaken }
            SortOrder.DATE_DESC -> filtered.sortedByDescending { it.dateTaken }
            SortOrder.NAME_ASC -> filtered.sortedBy { it.fileName }
            SortOrder.NAME_DESC -> filtered.sortedByDescending { it.fileName }
            SortOrder.RATING_DESC -> filtered.sortedByDescending { it.rating }
            SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeBytes }
        }

        return filtered
    }

    fun selectPhotoIndex(index: Int) {
        val filtered = _uiState.value.filteredPhotos
        if (index in filtered.indices) {
            _uiState.value = _uiState.value.copy(
                selectedPhotoId = filtered[index].id,
                isLoupeZoomed = false,
                zoomFactor = 1f
            )
            onSelectionChanged()
            refreshHistogram()
        }
    }

    fun nextPhoto() {
        val current = _uiState.value
        if (current.selectedIndex < current.filteredPhotos.size - 1) {
            selectPhotoIndex(current.selectedIndex + 1)
        }
    }

    fun previousPhoto() {
        val current = _uiState.value
        if (current.selectedIndex > 0) {
            selectPhotoIndex(current.selectedIndex - 1)
        }
    }

    fun setCompareIndex(index: Int) {
        val filtered = _uiState.value.filteredPhotos
        if (index in filtered.indices) {
            _uiState.value = _uiState.value.copy(comparePhotoId = filtered[index].id)
        }
    }

    fun toggleCompareMode() {
        val current = _uiState.value
        val newCompare = !current.isCompareMode
        _uiState.value = current.copy(
            isCompareMode = newCompare,
            viewMode = if (newCompare) DamViewMode.COMPARE else DamViewMode.LOUPE
        )
    }

    fun setViewMode(mode: DamViewMode) {
        _uiState.value = _uiState.value.copy(
            viewMode = mode,
            isCompareMode = (mode == DamViewMode.COMPARE)
        )
    }

    // --- CULLING ACTIONS ---
    fun togglePick() {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newStatus = if (photo.cullStatus == CullStatus.PICK) CullStatus.UNFLAGGED else CullStatus.PICK
        viewModelScope.launch {
            repository.setCullStatus(photo.id, newStatus)
        }
    }

    fun toggleReject() {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newStatus = if (photo.cullStatus == CullStatus.REJECT) CullStatus.UNFLAGGED else CullStatus.REJECT
        viewModelScope.launch {
            repository.setCullStatus(photo.id, newStatus)
        }
    }

    fun setCullStatus(status: CullStatus) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        viewModelScope.launch {
            repository.setCullStatus(photo.id, status)
        }
    }

    fun setRating(rating: Int) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newRating = if (photo.rating == rating) 0 else rating
        viewModelScope.launch {
            repository.setRating(photo.id, newRating)
        }
    }

    fun setColorLabel(label: ColorLabel) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val newLabel = if (photo.colorLabel == label) ColorLabel.NONE else label
        viewModelScope.launch {
            repository.setColorLabel(photo.id, newLabel)
        }
    }

    fun addTagToCurrent(tag: String) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        val trimmed = tag.trim()
        if (trimmed.isEmpty() || photo.tags.contains(trimmed)) return
        viewModelScope.launch {
            repository.batchAddTag(listOf(photo.id), trimmed)
        }
    }

    fun removeTagFromCurrent(tag: String) {
        if (!canEdit()) return
        val photo = _uiState.value.currentPhoto ?: return
        viewModelScope.launch {
            repository.removeTag(photo.id, tag)
        }
    }

    // --- FILTER ACTIONS ---
    fun updateFilter(filterModifier: (FilterOptions) -> FilterOptions) {
        applyVisiblePhotos(_uiState.value.photos, filterModifier(_uiState.value.filterOptions))
    }

    // --- SOURCE & DESTINATION FOLDERS (SAF & OTG SD CARD) ---
    private suspend fun restoreSession() {
        try {
            restoreSavedFolders()
        } finally {
            _uiState.update { it.copy(isRestoring = false) }
        }
    }

    private suspend fun restoreSavedFolders() {
        val persisted = withContext(Dispatchers.IO) { SafPermissions.persistedUris(appContext) }
        settings.pruneRecents(persisted)

        settings.lastDestination()?.takeIf { it.uri in persisted }?.let { destination ->
            _uiState.update {
                it.copy(destinationFolderUri = destination.uri, destinationFolderName = destination.displayName)
            }
        }

        val session = settings.librarySession()
        val restored = LibraryRestore.resolve(
            locations.getAll(),
            session.activeLocationId,
            session.activeFolder,
            session.selectedAssetId,
        )
        val location = restored.location ?: return
        pendingSelectionId = restored.selectedAssetId
        activate(location, restored.scope)
    }

    /** Shows the cached rows at once and starts the background scan (D12). */
    private suspend fun activate(location: LibraryLocation, activeScope: LibraryScope = LibraryScope.All) {
        val available = locations.isAvailable(location)
        setRoot(location.treeUri, location.displayName, isAvailable = available)
        _uiState.update { it.copy(activeLocationId = location.id, scope = activeScope) }
        scope.value = activeScope
        locations.touch(location.id)
        if (settings.librarySession().activeLocationId != location.id) settings.setActiveLocation(location.id)
        if (available && !location.isDemo) scanCoordinator.start(location.treeUri)
    }

    fun setScope(next: LibraryScope) {
        scope.value = next
        _uiState.update { it.copy(scope = next) }
        viewModelScope.launch { settings.setActiveFolder(next.savedFolder) }
    }

    /** Back in the foreground: the active folder is what's worth checking for changes (D12). */
    fun onAppStart() {
        val state = _uiState.value
        val root = state.currentRootUri ?: return
        if (state.isDemoSource || !state.isSourceAvailable) return
        scanCoordinator.rescanSubtree(root, state.scope.savedFolder)
    }

    private suspend fun isAvailable(uri: Uri): Boolean =
        withContext(Dispatchers.IO) { SafPermissions.isAvailable(appContext, uri) }

    private suspend fun refreshRecentAvailability() {
        val current = recents
        val rows = current.map {
            RecentSourceUi(it.uri, it.displayName, it.lastOpenedAt, isAvailable(Uri.parse(it.uri)))
        }
        _uiState.update { it.copy(recentSources = rows) }
    }

    /** Re-checks volumes after a resume or a media broadcast; a scan isn't reading while idle. */
    fun refreshSources() {
        viewModelScope.launch {
            _uiState.update { it.copy(storageEpoch = it.storageEpoch + 1) }
            refreshRecentAvailability()
            val state = _uiState.value
            val root = state.currentRootUri ?: return@launch
            if (state.isDemoSource || !state.isSourceAvailable) return@launch
            if (!isAvailable(Uri.parse(root)) && root == _uiState.value.currentRootUri) {
                scanCoordinator.cancel()
                _uiState.update { it.copy(isSourceAvailable = false) }
            }
        }
    }

    fun openRecentSource(uriString: String) {
        val uri = Uri.parse(uriString)
        viewModelScope.launch {
            if (isAvailable(uri)) {
                onSourceFolderSelected(uri)
            } else {
                val name = recents.firstOrNull { it.uri == uriString }?.displayName ?: "That source"
                showMessage("$name isn't connected")
            }
        }
    }

    fun retrySource() {
        val root = _uiState.value.currentRootUri ?: return
        viewModelScope.launch {
            if (isAvailable(Uri.parse(root))) {
                _uiState.update { it.copy(isSourceAvailable = true) }
                scanCoordinator.start(root)
            } else {
                showMessage("Still not connected")
            }
        }
    }

    fun rescan() {
        val state = _uiState.value
        val root = state.currentRootUri ?: return
        if (state.isDemoSource) return
        if (state.isSourceAvailable) scanCoordinator.start(root) else retrySource()
    }

    /** Back to Welcome; the next launch starts there too. */
    fun closeSource() {
        scanCoordinator.cancel()
        setRoot(null, "No source")
        _uiState.update { it.copy(activeLocationId = null, scope = LibraryScope.All) }
        scope.value = LibraryScope.All
        viewModelScope.launch {
            settings.clearLastSource()
            settings.setActiveLocation(null)
        }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    /** With the card gone there's nowhere to write sidecars, so the catalog is read-only. */
    private fun canEdit(): Boolean {
        val available = _uiState.value.isSourceAvailable
        if (!available) showMessage("Card disconnected. Reconnect it to flag or rate photos.")
        return available
    }

    fun onSourceFolderSelected(uri: Uri) {
        SafPermissions.takePersistable(appContext, uri)
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { SafPermissions.displayName(appContext, uri) }
            val evicted = settings.recordSourceOpened(RecentSource(uri.toString(), name, System.currentTimeMillis()))
            evicted.forEach { releaseIfUnused(it.uri) }
            val location = locations.add(uri.toString())
            if (location == null) showMessage("Android didn't grant access to that folder.") else activate(location)
        }
    }

    /** Evicted or replaced folders keep no grant unless a recent entry or the destination needs it. */
    private suspend fun releaseIfUnused(uri: String) {
        val inUse = uri == _uiState.value.destinationFolderUri ||
                settings.recentSources.first().any { it.uri == uri }
        if (!inUse) SafPermissions.release(appContext, Uri.parse(uri))
    }

    private fun setRoot(rootUri: String?, folderName: String, isAvailable: Boolean = true) {
        _uiState.update {
            it.copy(
                currentRootUri = rootUri,
                sourceFolderName = folderName,
                isSourceAvailable = isAvailable,
                photos = emptyList(),
                filteredPhotos = emptyList(),
                currentHistogram = null,
                selectedPhotoId = null,
                comparePhotoId = null,
                filterOptions = FilterOptions(),
                selectedPhotoIds = emptySet(),
                isMultiSelectMode = false,
            )
        }
        histogramKey = null
        currentRootUri.value = rootUri
    }

    private fun onScanState(state: ScanState) {
        val root = currentRootUri.value
        _uiState.update { current ->
            when (state) {
                ScanState.Idle -> current.copy(isScanning = false, scanProgress = null, scanStatusText = "")
                is ScanState.Listing -> if (state.rootUri != root) current else current.copy(
                    isScanning = true,
                    scanProgress = null,
                    scanStatusText = "Scanning: ${state.filesSeen} files in ${state.dirsVisited} folders",
                )
                is ScanState.Enriching -> if (state.rootUri != root) current else current.copy(
                    isScanning = true,
                    scanProgress = state.done.toFloat() / state.total,
                    scanStatusText = "Reading metadata ${state.done} / ${state.total}",
                )
                is ScanState.Complete -> if (state.rootUri != root) current else current.copy(
                    isScanning = false,
                    scanProgress = null,
                    scanStatusText = foundText(state.photoCount, state.folderCount),
                )
                is ScanState.SourceUnavailable -> if (state.rootUri != root) current else current.copy(
                    isScanning = false,
                    scanProgress = null,
                    scanStatusText = "",
                    isSourceAvailable = false,
                )
            }
        }
    }

    private fun foundText(photos: Int, folders: Int): String {
        val photoWord = if (photos == 1) "photo" else "photos"
        val folderWord = if (folders == 1) "folder" else "folders"
        return "Found $photos $photoWord in $folders $folderWord"
    }

    fun onDestinationFolderSelected(uri: Uri) {
        SafPermissions.takePersistable(appContext, uri)
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { SafPermissions.displayName(appContext, uri) }
            val previous = _uiState.value.destinationFolderUri
            _uiState.update { it.copy(destinationFolderUri = uri.toString(), destinationFolderName = name) }
            settings.setLastDestination(StoredFolder(uri.toString(), name))
            if (previous != null && previous != uri.toString()) releaseIfUnused(previous)
        }
    }

    fun loadSampleShoot() {
        scanCoordinator.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isScanning = true,
                scanStatusText = "Loading the demo shoot..."
            )
            repository.loadSampleShoot()
            activate(locations.demo())
            _uiState.value = _uiState.value.copy(isScanning = false, scanStatusText = "")
        }
    }

    // --- BATCH EXPORT ---
    fun openExportDialog() {
        if (!_uiState.value.isSourceAvailable) {
            showMessage("Card disconnected. Reconnect it to ingest photos.")
            return
        }
        _uiState.value = _uiState.value.copy(showExportDialog = true)
    }

    fun dismissExportDialog() {
        _uiState.value = _uiState.value.copy(showExportDialog = false)
    }

    fun openTagDialog() {
        _uiState.value = _uiState.value.copy(showTagDialog = true)
    }

    fun dismissTagDialog() {
        _uiState.value = _uiState.value.copy(showTagDialog = false)
    }

    fun toggleSyncErrorFilter() {
        updateFilter { it.copy(syncErrorsOnly = !it.syncErrorsOnly) }
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

    fun openSettings() {
        _uiState.update { it.copy(showSettings = true) }
    }

    fun dismissSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }

    fun setRejectAsNegativeRating(enabled: Boolean) {
        viewModelScope.launch { settings.setRejectAsNegativeRating(enabled) }
    }

    fun startBatchExport(config: ExportConfig) {
        exportJob?.cancel()
        val photosToExport = when (config.exportMode) {
            ExportMode.PICKS_ONLY -> _uiState.value.photos.filter { it.cullStatus == CullStatus.PICK }
            ExportMode.PICKS_OR_RATED -> _uiState.value.photos.filter {
                it.cullStatus == CullStatus.PICK || it.rating >= config.minRating
            }
            ExportMode.ALL_FLAGGED -> _uiState.value.photos.filter {
                it.cullStatus != CullStatus.UNFLAGGED || it.rating > 0 || it.colorLabel != ColorLabel.NONE
            }
            ExportMode.CUSTOM_FILTER -> _uiState.value.filteredPhotos
        }

        exportJob = viewModelScope.launch {
            repository.exportCulledFiles(photosToExport, config).collect { progress ->
                _uiState.value = _uiState.value.copy(exportProgress = progress)
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _uiState.value = _uiState.value.copy(
            exportProgress = ExportProgress(isExporting = false, errorMessage = "Export canceled by user")
        )
    }

    // --- MULTI-SELECT ---
    fun toggleMultiSelectMode() {
        val current = _uiState.value
        val newMode = !current.isMultiSelectMode
        _uiState.value = current.copy(
            isMultiSelectMode = newMode,
            selectedPhotoIds = if (!newMode) emptySet() else current.selectedPhotoIds
        )
    }

    fun togglePhotoSelection(id: String) {
        val current = _uiState.value
        val newSet = current.selectedPhotoIds.toMutableSet()
        if (newSet.contains(id)) newSet.remove(id) else newSet.add(id)
        _uiState.value = current.copy(selectedPhotoIds = newSet)
    }

    fun selectAll() {
        val ids = _uiState.value.filteredPhotos.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedPhotoIds = ids, isMultiSelectMode = true)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPhotoIds = emptySet())
    }

    fun batchPickSelected() {
        if (!canEdit()) return
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchSetCullStatus(ids, CullStatus.PICK)
        }
    }

    fun batchRejectSelected() {
        if (!canEdit()) return
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchSetCullStatus(ids, CullStatus.REJECT)
        }
    }

    fun batchRateSelected(rating: Int) {
        if (!canEdit()) return
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchSetRating(ids, rating)
        }
    }

    fun batchTagSelected(tag: String) {
        if (!canEdit()) return
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchAddTag(ids, tag)
        }
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
                companionUri = photo.companionFileUri?.let { Uri.parse(it) }
            )
            if (bitmap != null) {
                val hist = HistogramCalculator.compute(bitmap)
                _uiState.value = _uiState.value.copy(currentHistogram = hist)
            }
        }
    }

    companion object {
        private const val SAVE_SELECTION_DELAY_MS = 500L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RawCullApp
                RawCullViewModel(
                    appContext = app,
                    repository = app.container.catalogRepository,
                    settings = app.container.settingsRepository,
                    locations = app.container.locationRepository,
                    scanCoordinator = app.container.scanCoordinator,
                    writeQueue = app.container.xmpWriteQueue,
                )
            }
        }
    }
}
