package dev.shehran.rawcull.ui.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.importer.CardVolume
import dev.shehran.rawcull.data.importer.CardVolumes
import dev.shehran.rawcull.data.local.ImportPresetDao
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.repository.LocationChange
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.importer.ImportDestination
import dev.shehran.rawcull.importer.ImportSource
import dev.shehran.rawcull.ui.library.LocationUi
import dev.shehran.rawcull.util.SafPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val cards: List<CardVolume> = emptyList(),
    val source: ImportSource? = null,
    val presets: List<ImportPreset> = emptyList(),
    val selectedPresetId: Long? = null,
    val draft: ImportPreset = ImportPreset(name = ImportPreset.STANDARD_NAME),
    val locations: List<LocationUi> = emptyList(),
    val message: String? = null,
    val pendingCardPick: CardVolume? = null, // Waiting for the user to read the DCIM hint
) {
    val selectedPreset: ImportPreset? get() = presets.firstOrNull { it.id == selectedPresetId }
    val isDraftModified: Boolean get() = selectedPreset?.sameSettingsAs(draft) == false
    val destination: LibraryLocation?
        get() = locations.firstOrNull { it.location.id == draft.locationId }?.location
    val isDestinationAvailable: Boolean
        get() = locations.firstOrNull { it.location.id == draft.locationId }?.isAvailable == true
    val destinationLocations: List<LocationUi> get() = locations.filterNot { it.location.isDemo }
    val hasDestination: Boolean get() = destination != null && isDestinationAvailable
    val firstPhotoDate: Long? get() = null
    val canContinue: Boolean get() = false
}

/** Holds the wizard: the source, the destination and, once scanned, what to bring in. */
class ImportViewModel(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val presets: ImportPresetDao,
    private val volumes: CardVolumes,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var knownLocations: List<LibraryLocation> = emptyList()

    init {
        refreshCards()
        viewModelScope.launch {
            locations.observeAll().collect {
                knownLocations = it
                refreshLocations()
            }
        }
        viewModelScope.launch {
            presets.observeAll().collect { list ->
                _uiState.update { it.copy(presets = list) }
                ensurePresetSelected(list)
            }
        }
    }

    private suspend fun ensurePresetSelected(list: List<ImportPreset>) {
        val current = _uiState.value.selectedPresetId
        if (current != null && list.any { it.id == current }) return
        val lastId = settings.importPrefs.first().lastPresetId
        val preset = list.firstOrNull { it.id == lastId } ?: list.firstOrNull() ?: return
        selectPreset(preset.id)
    }

    fun selectPreset(id: Long) {
        viewModelScope.launch {
            val preset = presets.getById(id) ?: return@launch
            val libraryLocation = settings.librarySession().activeLocationId
            val locationId = ImportDestination.defaultLocationId(preset, knownLocations, libraryLocation)
            _uiState.update { it.copy(selectedPresetId = id, draft = preset.copy(locationId = locationId)) }
            settings.setLastPresetId(id)
        }
    }

    fun updateDraft(edit: (ImportPreset) -> ImportPreset) {
        _uiState.update { it.copy(draft = edit(it.draft)) }
    }

    fun saveDraft() {
        val state = _uiState.value
        val selected = state.selectedPreset ?: return
        viewModelScope.launch { presets.update(state.draft.copy(id = selected.id, name = selected.name)) }
    }

    fun saveDraftAs(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = presets.insert(_uiState.value.draft.copy(id = 0, name = trimmed))
            _uiState.update { it.copy(selectedPresetId = id) }
            settings.setLastPresetId(id)
        }
    }

    fun addLocation(uri: Uri) {
        viewModelScope.launch {
            val location = locations.add(uri.toString())
            if (location == null) {
                showMessage("Android didn't grant access to that folder.")
            } else {
                updateDraft { it.copy(locationId = location.id) }
            }
        }
    }

    fun relinkLocation(id: Long, uri: Uri) {
        viewModelScope.launch {
            when (val result = locations.relink(id, uri.toString())) {
                is LocationChange.Refused -> showMessage(result.message)
                LocationChange.Done -> refreshLocations()
            }
        }
    }

    private suspend fun refreshLocations() {
        val rows = knownLocations.map { LocationUi(it, locations.isAvailable(it)) }
        _uiState.update { state ->
            val usable = rows.filterNot { it.location.isDemo }
            val draft = state.draft.takeIf { draft -> usable.any { it.location.id == draft.locationId } }
                ?: state.draft.copy(locationId = usable.firstOrNull { it.isAvailable }?.location?.id)
            state.copy(locations = rows, draft = draft)
        }
    }

    /** A volume came or went, or the app came back to the front. */
    fun onStorageChanged() {
        refreshCards()
        viewModelScope.launch { refreshLocations() }
    }

    private fun refreshCards() {
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) { volumes.list() }
            _uiState.update { it.copy(cards = cards) }
        }
    }

    /** Opens a known card without the picker; otherwise explains what the picker needs. */
    fun onCardTapped(card: CardVolume) {
        viewModelScope.launch {
            val treeUri = card.uuid?.let { settings.volumeGrants.first()[it] }
            val usable = treeUri != null && withContext(Dispatchers.IO) {
                treeUri in SafPermissions.persistedUris(appContext) &&
                    SafPermissions.isAvailable(appContext, Uri.parse(treeUri))
            }
            if (usable) {
                selectSource(ImportSource(checkNotNull(treeUri), card.description, card.uuid))
            } else {
                _uiState.update { it.copy(pendingCardPick = card) }
            }
        }
    }

    fun dismissCardHint() {
        _uiState.update { it.copy(pendingCardPick = null) }
    }

    fun pickerIntentFor(card: CardVolume): Intent? = volumes.pickerIntent(card.uuid)

    fun onCardPicked(card: CardVolume, uri: Uri) {
        viewModelScope.launch {
            if (!SafPermissions.takePersistable(appContext, uri)) {
                showMessage("Android didn't grant access to that folder.")
                return@launch
            }
            card.uuid?.let { settings.setVolumeGrant(it, uri.toString()) }
            selectSource(ImportSource(uri.toString(), card.description, card.uuid))
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            if (!SafPermissions.takePersistable(appContext, uri)) {
                showMessage("Android didn't grant access to that folder.")
                return@launch
            }
            val label = withContext(Dispatchers.IO) { SafPermissions.displayName(appContext, uri) }
            selectSource(ImportSource(uri.toString(), label))
        }
    }

    private fun selectSource(source: ImportSource) {
        _uiState.update { it.copy(source = source, pendingCardPick = null) }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RawCullApp
                ImportViewModel(
                    appContext = app,
                    settings = app.container.settingsRepository,
                    locations = app.container.locationRepository,
                    presets = app.container.database.importPresetDao(),
                    volumes = app.container.cardVolumes,
                )
            }
        }
    }
}
