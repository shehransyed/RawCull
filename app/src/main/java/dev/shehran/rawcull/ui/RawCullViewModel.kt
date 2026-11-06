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
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.RecentSource
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.repository.StoredFolder
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.scan.ScanState
import dev.shehran.rawcull.util.HistogramCalculator
import dev.shehran.rawcull.util.HistogramData
import dev.shehran.rawcull.util.RawPreviewLoader
import dev.shehran.rawcull.util.SafPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

enum class DamViewMode {
    LOUPE,
    GRID,
    COMPARE
}

data class DamUiState(
    val photos: List<PhotoAsset> = emptyList(),
    val filteredPhotos: List<PhotoAsset> = emptyList(),
    val selectedIndex: Int = 0,
    val compareIndex: Int = 1,
    val isCompareMode: Boolean = false,
    val viewMode: DamViewMode = DamViewMode.LOUPE,
    val filterOptions: FilterOptions = FilterOptions(),
    val sourceFolderName: String = "No source",
    val currentRootUri: String? = null,
    val isSourceAvailable: Boolean = true,
    val destinationFolderName: String = "Not set",
    val destinationFolderUri: String? = null,
    val isScanning: Boolean = false,
    val scanProgress: Float? = null, // Null while the total isn't known yet
    val scanStatusText: String = "",
    val selectedPhotoIds: Set<String> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val showExportDialog: Boolean = false,
    val showTagDialog: Boolean = false,
    val exportProgress: ExportProgress = ExportProgress(),
    val currentHistogram: HistogramData? = null,
    val isLoupeZoomed: Boolean = false,
    val zoomFactor: Float = 1f
) {
    val currentPhoto: PhotoAsset?
        get() = filteredPhotos.getOrNull(selectedIndex)

    val comparePhoto: PhotoAsset?
        get() = filteredPhotos.getOrNull(compareIndex)

    val totalCount: Int get() = photos.size
    val picksCount: Int get() = photos.count { it.cullStatus == CullStatus.PICK }
    val rejectsCount: Int get() = photos.count { it.cullStatus == CullStatus.REJECT }
    val unflaggedCount: Int get() = photos.count { it.cullStatus == CullStatus.UNFLAGGED }
    val rawAndJpegCount: Int get() = photos.count { it.hasCompanion }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RawCullViewModel(
    private val appContext: Context,
    private val repository: RawCatalogRepository,
    private val settings: SettingsRepository,
    private val scanCoordinator: ScanCoordinator,
) : ViewModel() {
    private val currentRootUri = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(DamUiState())
    val uiState: StateFlow<DamUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null
    private var histogramJob: Job? = null

    init {
        viewModelScope.launch { restoreSession() }
        viewModelScope.launch { scanCoordinator.state.collect(::onScanState) }

        viewModelScope.launch {
            currentRootUri
                .flatMapLatest { root -> root?.let(repository::observeByRoot) ?: flowOf(emptyList()) }
                .collect(::updatePhotosList)
        }
    }

    private fun updatePhotosList(photoList: List<PhotoAsset>) {
        val current = _uiState.value
        val filtered = applyFilterAndSort(photoList, current.filterOptions)
        val newIndex = current.selectedIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
        val newCompare = current.compareIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))

        _uiState.value = current.copy(
            photos = photoList,
            filteredPhotos = filtered,
            selectedIndex = newIndex,
            compareIndex = if (newCompare == newIndex && filtered.size > 1) (newIndex + 1) % filtered.size else newCompare
        )
        refreshHistogram()
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

            cullMatch && ratingMatch && colorMatch && formatMatch && pairMatch && tagMatch && searchMatch
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
                selectedIndex = index,
                isLoupeZoomed = false,
                zoomFactor = 1f
            )
            scanCoordinator.prioritize(filtered[index].id)
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
            _uiState.value = _uiState.value.copy(compareIndex = index)
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
        val photo = _uiState.value.currentPhoto ?: return
        val newStatus = if (photo.cullStatus == CullStatus.PICK) CullStatus.UNFLAGGED else CullStatus.PICK
        viewModelScope.launch {
            repository.setCullStatus(photo.id, newStatus)
        }
    }

    fun toggleReject() {
        val photo = _uiState.value.currentPhoto ?: return
        val newStatus = if (photo.cullStatus == CullStatus.REJECT) CullStatus.UNFLAGGED else CullStatus.REJECT
        viewModelScope.launch {
            repository.setCullStatus(photo.id, newStatus)
        }
    }

    fun setCullStatus(status: CullStatus) {
        val photo = _uiState.value.currentPhoto ?: return
        viewModelScope.launch {
            repository.setCullStatus(photo.id, status)
        }
    }

    fun setRating(rating: Int) {
        val photo = _uiState.value.currentPhoto ?: return
        val newRating = if (photo.rating == rating) 0 else rating
        viewModelScope.launch {
            repository.setRating(photo.id, newRating)
        }
    }

    fun setColorLabel(label: ColorLabel) {
        val photo = _uiState.value.currentPhoto ?: return
        val newLabel = if (photo.colorLabel == label) ColorLabel.NONE else label
        viewModelScope.launch {
            repository.setColorLabel(photo.id, newLabel)
        }
    }

    fun addTagToCurrent(tag: String) {
        val photo = _uiState.value.currentPhoto ?: return
        val trimmed = tag.trim()
        if (trimmed.isEmpty() || photo.tags.contains(trimmed)) return
        viewModelScope.launch {
            repository.updatePhoto(photo.copy(tags = photo.tags + trimmed))
        }
    }

    fun removeTagFromCurrent(tag: String) {
        val photo = _uiState.value.currentPhoto ?: return
        viewModelScope.launch {
            repository.updatePhoto(photo.copy(tags = photo.tags - tag))
        }
    }

    // --- FILTER ACTIONS ---
    fun updateFilter(filterModifier: (FilterOptions) -> FilterOptions) {
        val newFilter = filterModifier(_uiState.value.filterOptions)
        _uiState.value = _uiState.value.copy(
            filterOptions = newFilter,
            filteredPhotos = applyFilterAndSort(_uiState.value.photos, newFilter),
            selectedIndex = 0
        )
        refreshHistogram()
    }

    // --- SOURCE & DESTINATION FOLDERS (SAF & OTG SD CARD) ---
    private suspend fun restoreSession() {
        val persisted = withContext(Dispatchers.IO) { SafPermissions.persistedUris(appContext) }
        settings.pruneRecents(persisted)

        settings.lastDestination()?.takeIf { it.uri in persisted }?.let { destination ->
            _uiState.update {
                it.copy(destinationFolderUri = destination.uri, destinationFolderName = destination.displayName)
            }
        }

        val source = settings.lastSource()?.takeIf { it.uri in persisted } ?: return
        val uri = Uri.parse(source.uri)
        if (withContext(Dispatchers.IO) { SafPermissions.isAvailable(appContext, uri) }) {
            openSource(uri, source.displayName)
        } else {
            setRoot(source.uri, source.displayName, isAvailable = false)
        }
    }

    fun onSourceFolderSelected(uri: Uri) {
        SafPermissions.takePersistable(appContext, uri)
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { SafPermissions.displayName(appContext, uri) }
            val evicted = settings.recordSourceOpened(RecentSource(uri.toString(), name, System.currentTimeMillis()))
            evicted.forEach { releaseIfUnused(it.uri) }
            openSource(uri, name)
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
                selectedIndex = 0,
                compareIndex = 1,
                filterOptions = FilterOptions(),
                selectedPhotoIds = emptySet(),
                isMultiSelectMode = false,
            )
        }
        currentRootUri.value = rootUri
    }

    private fun openSource(uri: Uri, folderName: String) {
        setRoot(uri.toString(), folderName)
        scanCoordinator.start(uri.toString())
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
            setRoot(DEMO_ROOT_URI, "Demo shoot")
            _uiState.value = _uiState.value.copy(isScanning = false, scanStatusText = "")
        }
    }

    // --- BATCH EXPORT ---
    fun openExportDialog() {
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
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchSetCullStatus(ids, CullStatus.PICK)
        }
    }

    fun batchRejectSelected() {
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchSetCullStatus(ids, CullStatus.REJECT)
        }
    }

    fun batchRateSelected(rating: Int) {
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchSetRating(ids, rating)
        }
    }

    fun batchTagSelected(tag: String) {
        val ids = _uiState.value.selectedPhotoIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.batchAddTag(ids, tag)
        }
    }

    private fun refreshHistogram() {
        histogramJob?.cancel()
        val photo = _uiState.value.currentPhoto ?: return
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RawCullApp
                RawCullViewModel(
                    appContext = app,
                    repository = app.container.catalogRepository,
                    settings = app.container.settingsRepository,
                    scanCoordinator = app.container.scanCoordinator,
                )
            }
        }
    }
}
