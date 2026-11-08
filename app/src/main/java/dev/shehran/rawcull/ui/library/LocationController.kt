package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.data.repository.LocationChange
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.PhotoCatalog
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.scan.ScanState
import dev.shehran.rawcull.library.FolderTree
import dev.shehran.rawcull.library.LibraryRestore
import dev.shehran.rawcull.library.LibraryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The active Location and scope: restore on launch, add/relink/switch, availability and scanning. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LocationController(
    private val store: LibraryStore,
    private val repository: PhotoCatalog,
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val collections: CollectionRepository,
    private val jobs: ImportJobDao,
    private val scanCoordinator: ScanCoordinator,
    private val startupFinished: suspend () -> Unit,
    private val view: ViewController,
    private val culling: CullingController,
    private val deletion: DeletionController,
    private val histogram: HistogramLoader,
) {
    private val currentLocation = MutableStateFlow<LibraryLocation?>(null)
    private val scope = MutableStateFlow<LibraryScope>(LibraryScope.All)
    private var knownLocations: List<LibraryLocation> = emptyList()

    /** Restores the last session and follows scans and the Location list. Called first. */
    fun startSession() {
        store.scope.launch { restoreSession() }
        store.scope.launch { scanCoordinator.state.collect(::onScanState) }
        store.scope.launch {
            locations.observeAll().collect {
                knownLocations = it
                refreshAvailability()
                onLocationsChanged(it)
            }
        }
    }

    /** Feeds the scope's rows into the view. */
    fun observeRows() {
        store.scope.launch {
            combine(currentLocation, scope) { location, activeScope -> location?.treeUri to activeScope }
                .flatMapLatest { (root, activeScope) ->
                    root?.let { repository.observeScope(it, activeScope) } ?: flowOf(emptyList())
                }
                .collect { view.rows.value = it }
        }
    }

    /** The folder tree, its counts and the latest import of the active Location. */
    fun observeFolders() {
        store.scope.launch {
            currentLocation
                .flatMapLatest { location ->
                    location?.let { repository.observeFolderCounts(it.treeUri) } ?: flowOf(emptyMap())
                }
                .map { counts -> FolderTree.build(counts) to counts.values.sum() }
                .flowOn(Dispatchers.Default)
                .collect { (tree, total) ->
                    store.update { it.copy(folderTree = tree, locationPhotoCount = total) }
                }
        }
        store.scope.launch {
            currentLocation
                .flatMapLatest { location -> location?.let { jobs.observeLatestJob(it.id) } ?: flowOf(null) }
                .collect { job -> store.update { it.copy(latestImport = job) } }
        }
        store.scope.launch {
            currentLocation
                .flatMapLatest { location -> location?.let { jobs.observeLatestJob(it.id) } ?: flowOf(null) }
                .flatMapLatest { job -> job?.let { repository.observeImportPhotoCount(it.id) } ?: flowOf(0) }
                .collect { count -> store.update { it.copy(latestImportPhotoCount = count) } }
        }
    }

    private suspend fun restoreSession() {
        try {
            startupFinished()
            val session = settings.librarySession()
            val restored = LibraryRestore.resolve(
                locations.getAll(),
                session.activeLocationId,
                session.activeFolder,
                session.selectedAssetId,
                session.activeCollectionId,
            )
            restored.location?.let { location ->
                view.pendingSelectionId = restored.selectedAssetId
                activate(location, keepExisting(location, restored.scope))
            }
        } finally {
            store.update { it.copy(isRestoring = false) }
        }
    }

    /** A saved collection may have been deleted since; then the Library opens on everything. */
    private suspend fun keepExisting(location: LibraryLocation, scope: LibraryScope): LibraryScope {
        if (scope !is LibraryScope.Collection) return scope
        val row = collections.get(scope.id)
        return if (row != null && row.locationId == PhotoCollection.locationKey(location)) scope else LibraryScope.All
    }

    /** Shows the cached rows at once and starts the background scan (D12). */
    private suspend fun activate(location: LibraryLocation, activeScope: LibraryScope = LibraryScope.All) {
        val available = locations.isAvailable(location)
        val switching = store.value.activeLocation?.id != location.id
        histogram.reset()
        if (switching) {
            culling.clearHistory()
            deletion.forgetTrashed()
            view.switchTo(location)
        }
        store.update {
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
                filterOptions = view.filter.value,
                filtersEnabled = view.filtersEnabled.value,
                canUndo = culling.canUndo,
                canRedo = culling.canRedo,
                selectedPhotoIds = emptySet(),
            )
        }
        currentLocation.value = location
        scope.value = activeScope
        locations.touch(location.id)
        if (settings.librarySession().activeLocationId != location.id) settings.setActiveLocation(location.id)
        saveScope(activeScope)
        val expanded = settings.expandedFolders(location.id)
        store.update { it.copy(expandedFolders = expanded) }

        if (location.isDemo) {
            scanCoordinator.cancel()
            repository.loadSampleShoot()
        } else if (available) {
            scanCoordinator.start(location.treeUri)
        }
    }

    /** Settings can remove or relink what's on screen; the Library has to follow. */
    private suspend fun onLocationsChanged(all: List<LibraryLocation>) {
        val active = store.value.activeLocation ?: return
        val current = all.firstOrNull { it.id == active.id }
        when {
            current == null -> leaveRemovedLocation(all)
            current.treeUri != active.treeUri -> activate(current)
            current.displayName != active.displayName ->
                store.update { it.copy(activeLocation = current) }.also { currentLocation.value = current }
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
        store.update {
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
        store.scope.launch {
            val location = locations.get(id) ?: return@launch
            if (location.id == store.value.activeLocation?.id) return@launch
            activate(location)
        }
    }

    fun addLocation(uri: String) {
        store.scope.launch {
            val location = locations.add(uri)
            if (location == null) store.showMessage("Android didn't grant access to that folder.") else activate(location)
        }
    }

    fun relinkLocation(id: Long, uri: String) {
        store.scope.launch {
            when (val result = locations.relink(id, uri)) {
                is LocationChange.Refused -> store.showMessage(result.message)
                LocationChange.Done -> {
                    refreshAvailability()
                    if (store.value.activeLocation?.id == id) locations.get(id)?.let { activate(it) }
                }
            }
        }
    }

    fun openDemoLibrary() {
        store.scope.launch { activate(locations.demo()) }
    }

    fun setScope(next: LibraryScope) {
        if (next == scope.value) return
        scope.value = next
        store.update { it.copy(scope = next, selectedPhotoIds = emptySet()) }
        store.scope.launch { saveScope(next) }
    }

    private suspend fun saveScope(scope: LibraryScope) {
        settings.setActiveFolder(scope.savedFolder)
        settings.setActiveCollection(scope.savedCollectionId)
    }

    /** "Cull in Library" after an import: opens that Location on what was just imported. */
    fun openImport(job: ImportJob) {
        store.scope.launch {
            val location = locations.get(job.locationId) ?: return@launch
            activate(location, LibraryScope.LatestImport(job.id))
            store.setViewMode(DamViewMode.GRID)
        }
    }

    fun toggleFolder(path: String) {
        val location = store.value.activeLocation ?: return
        val expanded = store.value.expandedFolders.let { if (path in it) it - path else it + path }
        store.update { it.copy(expandedFolders = expanded) }
        store.scope.launch { settings.setExpandedFolders(location.id, expanded) }
    }

    private suspend fun refreshAvailability() {
        val rows = knownLocations.map { LocationUi(it, locations.isAvailable(it)) }
        store.update { it.copy(locations = rows) }
    }

    /** Re-checks volumes after a resume or a media broadcast; a scan isn't reading while idle. */
    fun refreshSources() {
        store.scope.launch {
            refreshAvailability()
            val location = store.value.activeLocation ?: return@launch
            val available = locations.isAvailable(location)
            if (location.id != store.value.activeLocation?.id) return@launch
            val wasAvailable = store.value.isLocationAvailable
            if (!available && wasAvailable) scanCoordinator.cancel()
            store.update { it.copy(isLocationAvailable = available) }
            if (available && !wasAvailable && !location.isDemo) scanCoordinator.start(location.treeUri)
        }
    }

    fun onPullToRefresh() {
        if (!rescanActiveFolder()) return
        store.update { it.copy(isRefreshing = true) }
    }

    /** Back in the foreground: the active folder is what's worth checking for changes (D12). */
    fun rescanActiveFolder(): Boolean {
        val state = store.value
        val root = state.rootUri ?: return false
        if (state.isDemo || !state.isLocationAvailable) return false
        scanCoordinator.rescanSubtree(root, state.scope.savedFolder)
        return true
    }

    fun rescan() {
        val state = store.value
        val root = state.rootUri ?: return
        if (state.isDemo) return
        if (state.isLocationAvailable) scanCoordinator.start(root) else retryLocation()
    }

    fun retryLocation() {
        val location = store.value.activeLocation ?: return
        store.scope.launch {
            if (locations.isAvailable(location)) {
                store.update { it.copy(isLocationAvailable = true) }
                refreshAvailability()
                scanCoordinator.start(location.treeUri)
            } else {
                store.showMessage("Still not connected")
            }
        }
    }

    private fun onScanState(state: ScanState) {
        val root = currentLocation.value?.treeUri
        store.update { current ->
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
}
