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

data class SettingsUiState(
    val locations: List<LocationUi> = emptyList(),
    val presets: List<ImportPreset> = emptyList(),
    val importPrefs: ImportPrefs = ImportPrefs(),
    val rejectAsNegativeRating: Boolean = true,
    val cullingPrefs: CullingPrefs = CullingPrefs(),
    val message: String? = null,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val presets: ImportPresetDao,
    private val catalog: RawCatalogRepository,
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
        viewModelScope.launch { presets.observeAll().collect { list -> _uiState.update { it.copy(presets = list) } } }
        viewModelScope.launch { settings.importPrefs.collect { prefs -> _uiState.update { it.copy(importPrefs = prefs) } } }
        viewModelScope.launch { settings.cullingPrefs.collect { prefs -> _uiState.update { it.copy(cullingPrefs = prefs) } } }
        viewModelScope.launch {
            settings.xmpPrefs.collect { prefs ->
                _uiState.update { it.copy(rejectAsNegativeRating = prefs.rejectAsNegativeRating) }
            }
        }
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            val rows = knownLocations.map { LocationUi(it, locations.isAvailable(it)) }
            _uiState.update { it.copy(locations = rows) }
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
                )
            }
        }
    }
}
