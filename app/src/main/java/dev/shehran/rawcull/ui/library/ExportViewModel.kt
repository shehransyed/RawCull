package dev.shehran.rawcull.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.export.ExportController
import dev.shehran.rawcull.data.export.ExportOptions
import dev.shehran.rawcull.data.export.ExportRequest
import dev.shehran.rawcull.data.export.ExportStatus
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.library.ExportContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The *Export in order…* dialog (R7 D9). [shownIds] is set when filters hide part of the open collection. */
data class ExportDialogState(
    val collectionId: Long,
    val collectionName: String,
    val sort: SortOrder,
    val content: ExportContent,
    val keepOrder: Boolean,
    val shownIds: List<String>?,
    val onlyShown: Boolean = false,
    val refusal: String? = null,
)

/**
 * Ordered export for the Library: the dialog's choices (remembered as `export_content` and
 * `export_keep_order`), then the job's progress and summary from [ExportController].
 */
class ExportViewModel(
    private val settings: SettingsRepository,
    private val controller: ExportController,
) : ViewModel() {
    private val _dialog = MutableStateFlow<ExportDialogState?>(null)
    val dialog: StateFlow<ExportDialogState?> = _dialog.asStateFlow()
    val status: StateFlow<ExportStatus?> = controller.status

    /** "Only the n shown" is offered when [collection] is open and the filters hide some of it. */
    fun open(collection: PhotoCollection, library: LibraryUiState) {
        if (collection.isSet) return
        val isOpen = library.activeCollection?.id == collection.id
        val shown = library.filteredPhotos.map { it.id }.takeIf { isOpen && library.filtersHide }
        val sort = if (isOpen) library.sortOrder else SortOrder.forCollection(collection.sortOrder)
        viewModelScope.launch {
            val (content, keepOrder) = settings.exportChoices.first()
            _dialog.value = ExportDialogState(collection.id, collection.name, sort, ExportContent.parse(content), keepOrder, shown)
        }
    }

    fun setContent(content: ExportContent) = _dialog.update { it?.copy(content = content) }

    fun setKeepOrder(keep: Boolean) = _dialog.update { it?.copy(keepOrder = keep) }

    fun setOnlyShown(only: Boolean) = _dialog.update { it?.copy(onlyShown = only && it.shownIds != null) }

    fun dismiss() {
        _dialog.value = null
    }

    /** The folder was picked: remembers the choices and queues the job. */
    fun start(treeUri: String) {
        val state = _dialog.value ?: return
        val request = ExportRequest(
            treeUri = treeUri,
            collectionId = state.collectionId,
            collectionName = state.collectionName,
            sort = state.sort,
            onlyIds = state.shownIds?.takeIf { state.onlyShown },
            options = ExportOptions(state.content, state.keepOrder),
        )
        viewModelScope.launch { settings.setExportChoices(state.content.name, state.keepOrder) }
        if (controller.start(request)) {
            _dialog.value = null
        } else {
            _dialog.value = state.copy(refusal = "Another export is still running.")
        }
    }

    fun cancel() = controller.cancel()

    fun dismissSummary() = controller.dismiss()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RawCullApp
                ExportViewModel(app.container.settingsRepository, app.container.exportController)
            }
        }
    }
}
