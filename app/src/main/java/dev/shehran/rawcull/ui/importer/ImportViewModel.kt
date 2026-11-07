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
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.importer.ImportDestination
import dev.shehran.rawcull.importer.ImportPreview
import dev.shehran.rawcull.importer.ImportSession
import dev.shehran.rawcull.importer.ImportSessionState
import dev.shehran.rawcull.importer.ImportSource
import dev.shehran.rawcull.importer.PreviewDay
import dev.shehran.rawcull.importer.PreviewOptions
import dev.shehran.rawcull.importer.PreviewSummary
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
    val session: ImportSessionState = ImportSessionState.Idle,
    val options: PreviewOptions = PreviewOptions(),
    val overrides: Set<String> = emptySet(), // See ImportPreview
    val collapsedDays: Set<String> = emptySet(),
    val days: List<PreviewDay> = emptyList(),
    val summary: PreviewSummary = PreviewSummary(),
    val anchorId: String? = null, // Where a range selection starts
    val viewerId: String? = null,
    val inspectedId: String? = null,
    val destinationFreeBytes: Long? = null,
    val deleteFromCard: Boolean = false,
) {
    val candidates: List<ImportCandidate> get() = session.candidateList
    val viewerCandidate: ImportCandidate? get() = viewerId?.let { id -> candidates.firstOrNull { it.id == id } }
    val inspected: ImportCandidate? get() = inspectedId?.let { id -> candidates.firstOrNull { it.id == id } }
    val isShortOfSpace: Boolean get() = ImportPreview.isShortOfSpace(summary.bytes, destinationFreeBytes)
    val canStart: Boolean
        get() = summary.selectedPhotos > 0 && hasDestination && !isShortOfSpace &&
            session != ImportSessionState.SourceLost
    val selectedPreset: ImportPreset? get() = presets.firstOrNull { it.id == selectedPresetId }
    val isDraftModified: Boolean get() = selectedPreset?.sameSettingsAs(draft) == false
    val destination: LibraryLocation?
        get() = locations.firstOrNull { it.location.id == draft.locationId }?.location
    val isDestinationAvailable: Boolean
        get() = locations.firstOrNull { it.location.id == draft.locationId }?.isAvailable == true
    val destinationLocations: List<LocationUi> get() = locations.filterNot { it.location.isDemo }
    val hasDestination: Boolean get() = destination != null && isDestinationAvailable
    val firstPhotoDate: Long? get() = candidates.mapNotNull { it.folderDate }.minOrNull()
    val canContinue: Boolean get() = candidates.isNotEmpty() && hasDestination
}

/** Holds the wizard: the source, the destination and, once scanned, what to bring in. */
class ImportViewModel(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val locations: LocationRepository,
    private val presets: ImportPresetDao,
    private val volumes: CardVolumes,
    private val session: ImportSession,
    private val documents: DocumentIo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var knownLocations: List<LibraryLocation> = emptyList()
    private var hideImportedTouched = false

    init {
        refreshCards()
        viewModelScope.launch {
            session.state.collect { state -> updatePreview { it.copy(session = state) } }
        }
        viewModelScope.launch {
            settings.importPrefs.collect { prefs ->
                _uiState.update { it.copy(deleteFromCard = prefs.deleteFromCardAfterImport) }
                if (!hideImportedTouched) {
                    updatePreview { it.copy(options = it.options.copy(hideImported = prefs.hideAlreadyImported)) }
                }
            }
        }
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
        session.onStorageChanged()
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
        // Ticks belong to what's on a source, so a different one starts clean.
        val sameSource = _uiState.value.source?.treeUri == source.treeUri
        updatePreview {
            it.copy(
                source = source,
                pendingCardPick = null,
                overrides = if (sameSource) it.overrides else emptySet(),
                collapsedDays = if (sameSource) it.collapsedDays else emptySet(),
                viewerId = null,
                inspectedId = null,
                anchorId = null,
            )
        }
        session.start(source)
    }

    /** Applies [edit] and recomputes what follows from candidates, toggles and ticks. */
    private fun updatePreview(edit: (ImportUiState) -> ImportUiState) {
        _uiState.update { current ->
            val next = edit(current)
            next.copy(
                days = ImportPreview.days(next.candidates, next.options),
                summary = ImportPreview.summary(next.candidates, next.options, next.overrides),
            )
        }
    }

    fun setIncludeRaw(include: Boolean) = updatePreview { it.copy(options = it.options.copy(includeRaw = include)) }

    fun setIncludeRaster(include: Boolean) = updatePreview { it.copy(options = it.options.copy(includeRaster = include)) }

    fun setHideImported(hide: Boolean) {
        hideImportedTouched = true
        updatePreview { it.copy(options = it.options.copy(hideImported = hide)) }
    }

    fun selectAll(selected: Boolean) = updatePreview {
        it.copy(overrides = ImportPreview.setSelected(ImportPreview.visible(it.candidates, it.options), selected, it.options, it.overrides))
    }

    fun setDaySelected(day: PreviewDay, selected: Boolean) = updatePreview {
        it.copy(overrides = ImportPreview.setSelected(day.candidates, selected, it.options, it.overrides))
    }

    fun toggleSelected(candidate: ImportCandidate) = updatePreview {
        val selected = ImportPreview.isSelected(candidate, it.options, it.overrides)
        it.copy(
            overrides = ImportPreview.setSelected(listOf(candidate), !selected, it.options, it.overrides),
            anchorId = candidate.id,
        )
    }

    /** Long-press or Shift+click: ticks everything between the last tile touched and this one. */
    fun extendSelection(id: String) = updatePreview { state ->
        val ordered = state.days.flatMap { it.candidates }
        val anchor = state.anchorId
        val targets = if (anchor == null) {
            ordered.filter { it.id == id }
        } else {
            ImportPreview.range(ordered, anchor, id)
        }
        state.copy(overrides = ImportPreview.setSelected(targets, true, state.options, state.overrides), anchorId = id)
    }

    fun toggleDayCollapsed(key: String) {
        _uiState.update {
            it.copy(collapsedDays = if (key in it.collapsedDays) it.collapsedDays - key else it.collapsedDays + key)
        }
    }

    fun openViewer(id: String) {
        _uiState.update { it.copy(viewerId = id, inspectedId = id) }
    }

    fun closeViewer() {
        _uiState.update { it.copy(viewerId = null) }
    }

    fun stepViewer(delta: Int) {
        _uiState.update { state ->
            val ordered = state.days.flatMap { it.candidates }
            val index = ordered.indexOfFirst { it.id == state.viewerId }
            val next = ordered.getOrNull(index + delta) ?: return@update state
            state.copy(viewerId = next.id, inspectedId = next.id)
        }
    }

    fun refreshFreeSpace() {
        val treeUri = _uiState.value.destination?.treeUri ?: return
        viewModelScope.launch {
            val free = withContext(Dispatchers.IO) { documents.availableBytes(treeUri) }
            _uiState.update { it.copy(destinationFreeBytes = free) }
        }
    }

    fun startImport() {
        showMessage("Import starts in the next step of this round.")
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
                    session = app.container.importSession,
                    documents = app.container.documentIo,
                )
            }
        }
    }
}
