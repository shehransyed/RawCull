package dev.shehran.rawcull.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.AppContainer
import dev.shehran.rawcull.data.local.ImportPresetDao
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.data.repository.CullingPrefs
import dev.shehran.rawcull.data.repository.ImportPrefs
import dev.shehran.rawcull.data.repository.LocationChange
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.ui.library.LocationUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.data.preview.PreviewWarmup
import dev.shehran.rawcull.data.preview.WarmupState
import android.util.Log
import dev.shehran.rawcull.BuildConfig
import dev.shehran.rawcull.data.preview.BenchmarkTable
import dev.shehran.rawcull.data.preview.PreviewBenchmark
import dev.shehran.rawcull.data.preview.PreviewRepository

data class SettingsUiState(
    val locations: List<LocationUi> = emptyList(),
    val presets: List<ImportPreset> = emptyList(),
    val importPrefs: ImportPrefs = ImportPrefs(),
    val rejectAsNegativeRating: Boolean = true,
    val cullingPrefs: CullingPrefs = CullingPrefs(),
    /** The Location the Library has open, which *Build previews* works on; null for none or the demo. */
    val previewLocation: LibraryLocation? = null,
    val warmup: WarmupState = WarmupState(),
    val previewCacheBytes: Long = 0,
    val previewCacheLimitMb: Int = SettingsRepository.DEFAULT_PREVIEW_CACHE_LIMIT_MB,
    val showPreviewTiming: Boolean = false,
    val benchmark: BenchmarkUi = BenchmarkUi(),
    val message: String? = null,
)

/** The debug benchmark: progress while it runs, then the table to copy. */
data class BenchmarkUi(val isRunning: Boolean = false, val done: Int = 0, val total: Int = 0, val table: String? = null)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val presets: ImportPresetDao,
    private val catalog: RawCatalogRepository,
    private val warmup: PreviewWarmup,
    private val previews: PreviewRepository,
    private val log: (String) -> Unit = { Log.i("RawCull.Preview", it) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var knownLocations: List<LibraryLocation> = emptyList()

    init {
        viewModelScope.launch {
            locations.observeAll().collect {
                knownLocations = it
                refreshAvailability()
            }
        }
        viewModelScope.launch { warmup.state.collect { state -> _uiState.update { it.copy(warmup = state) } } }
        viewModelScope.launch { previews.observeCacheBytes().collect { bytes -> _uiState.update { it.copy(previewCacheBytes = bytes) } } }
        viewModelScope.launch { settings.previewCacheLimitMb.collect { mb -> _uiState.update { it.copy(previewCacheLimitMb = mb) } } }
        viewModelScope.launch { settings.showPreviewTiming.collect { on -> _uiState.update { it.copy(showPreviewTiming = on) } } }
        viewModelScope.launch { presets.observeAll().collect { list -> _uiState.update { it.copy(presets = list) } } }
        viewModelScope.launch { settings.importPrefs.collect { prefs -> _uiState.update { it.copy(importPrefs = prefs) } } }
        viewModelScope.launch { settings.cullingPrefs.collect { prefs -> _uiState.update { it.copy(cullingPrefs = prefs) } } }
        viewModelScope.launch {
            settings.xmpPrefs.collect { prefs ->
                _uiState.update { it.copy(rejectAsNegativeRating = prefs.rejectAsNegativeRating) }
            }
        }
    }

    /** D12: thumbnails for every photo in the Library's open Location, ahead of time. */
    fun buildPreviews() {
        val location = _uiState.value.previewLocation ?: return
        viewModelScope.launch {
            val photos = catalog.photosIn(location.treeUri)
            warmup.start(location.displayName, photos.map(PreviewKeys::source))
        }
    }

    fun setPreviewCacheLimit(mb: Int) {
        viewModelScope.launch { settings.setPreviewCacheLimitMb(mb) }
    }

    fun clearPreviewCache() {
        viewModelScope.launch {
            warmup.cancel()
            previews.clearCache()
            showMessage("Preview cache cleared")
        }
    }

    fun setShowPreviewTiming(enabled: Boolean) {
        viewModelScope.launch { settings.setShowPreviewTiming(enabled) }
    }

    /** R5.9: cold and warm runs over the Library's open folder, as a table of p50/p90 per stage and format. */
    fun benchmarkFolder(fitWidth: Int, fitHeight: Int) {
        if (_uiState.value.benchmark.isRunning) return
        viewModelScope.launch {
            val session = settings.librarySession()
            val location = knownLocations.firstOrNull { it.id == session.activeLocationId && !it.isDemo }
            if (location == null) {
                showMessage("Open a folder in the Library first")
                return@launch
            }
            val photos = catalog.photosInFolder(location.treeUri, session.activeFolder).map {
                PreviewBenchmark.Photo(PreviewKeys.source(it), if (it.hasCompanion) "${it.format.name}+${it.companionFormat?.name ?: "JPG"}" else it.format.name)
            }
            if (photos.isEmpty()) {
                showMessage("No photos in this folder")
                return@launch
            }
            warmup.cancel()
            _uiState.update { it.copy(benchmark = BenchmarkUi(isRunning = true, total = photos.size)) }
            val samples = PreviewBenchmark(previews).run(photos, fitWidth, fitHeight) { done, total ->
                _uiState.update { it.copy(benchmark = BenchmarkUi(isRunning = true, done = done, total = total)) }
            }
            val folder = session.activeFolder.ifEmpty { location.displayName }
            val table = BenchmarkTable.format(samples, "RawCull ${BuildConfig.VERSION_NAME} · $folder · ${photos.size} photos · fit ${fitWidth}×$fitHeight")
            table.lines().forEach(log)
            _uiState.update { it.copy(benchmark = BenchmarkUi(table = table)) }
        }
    }

    fun dismissBenchmark() {
        _uiState.update { it.copy(benchmark = BenchmarkUi()) }
    }

    fun pauseWarmup() = warmup.pause()

    fun resumeWarmup() = warmup.resume()

    fun cancelWarmup() = warmup.cancel()

    fun refreshAvailability() {
        viewModelScope.launch {
            val rows = knownLocations.map { LocationUi(it, locations.isAvailable(it)) }
            val activeId = settings.librarySession().activeLocationId
            val real = knownLocations.filterNot { it.isDemo }
            val previewLocation = real.firstOrNull { it.id == activeId } ?: real.singleOrNull()
            _uiState.update { it.copy(locations = rows, previewLocation = previewLocation) }
        }
    }

    fun addLocation(uri: Uri) {
        viewModelScope.launch {
            if (locations.add(uri.toString()) == null) showMessage("Android didn't grant access to that folder.")
        }
    }

    fun renameLocation(id: Long, name: String) {
        viewModelScope.launch { locations.rename(id, name) }
    }

    fun relinkLocation(id: Long, uri: Uri) = change { locations.relink(id, uri.toString()) }

    fun removeLocation(id: Long) = change { locations.remove(id) }

    private fun change(action: suspend () -> LocationChange) {
        viewModelScope.launch {
            (action() as? LocationChange.Refused)?.let { showMessage(it.message) }
            refreshAvailability()
        }
    }

    fun setVerifyMode(mode: VerifyMode) {
        viewModelScope.launch { settings.setVerifyMode(mode) }
    }

    fun setDeleteFromCard(enabled: Boolean) {
        viewModelScope.launch { settings.setDeleteFromCardAfterImport(enabled) }
    }

    fun setHideAlreadyImported(enabled: Boolean) {
        viewModelScope.launch { settings.setHideAlreadyImported(enabled) }
    }

    fun setRejectAsNegativeRating(enabled: Boolean) {
        viewModelScope.launch { settings.setRejectAsNegativeRating(enabled) }
    }

    fun setAutoAdvance(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoAdvance(enabled) }
    }

    fun setSwipeToFlag(enabled: Boolean) {
        viewModelScope.launch { settings.setSwipeToFlag(enabled) }
    }

    fun renamePreset(preset: ImportPreset, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || preset.isStandard) return
        viewModelScope.launch { presets.update(preset.copy(name = trimmed)) }
    }

    fun deletePreset(preset: ImportPreset) {
        if (preset.isStandard) return
        viewModelScope.launch { presets.delete(preset) }
    }

    /** Debug builds only; see D12. */
    fun addSyntheticPhotos() {
        viewModelScope.launch {
            catalog.addSyntheticPhotos(SYNTHETIC_COUNT)
            showMessage("Added 50,000 synthetic photos to the demo library")
        }
    }

    fun removeSyntheticPhotos() {
        viewModelScope.launch {
            catalog.removeSyntheticPhotos()
            showMessage("Removed the synthetic photos")
        }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        private const val SYNTHETIC_COUNT = 50_000

        /** Takes [container] directly: Nav3's entry store has no application in its creation extras. */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = container.settingsRepository,
                    locations = container.locationRepository,
                    presets = container.database.importPresetDao(),
                    catalog = container.catalogRepository,
                    warmup = container.previewWarmup,
                    previews = container.previewRepository,
                )
            }
        }
    }
}
