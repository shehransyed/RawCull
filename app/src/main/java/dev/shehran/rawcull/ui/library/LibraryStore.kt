package dev.shehran.rawcull.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Library's controllers share: the ViewModel's scope, its one state and its log.
 * Each controller owns a slice of the behaviour; [LibraryViewModel] stays the façade the UI talks to.
 */
internal class LibraryStore(
    val scope: CoroutineScope,
    val log: (String) -> Unit,
) {
    val state = MutableStateFlow(LibraryUiState())

    val value: LibraryUiState get() = state.value

    fun update(transform: (LibraryUiState) -> LibraryUiState) = state.update(transform)

    /** A plain message: it must not inherit the action of whatever it replaces. */
    fun showMessage(message: String) {
        update { it.copy(snackbarMessage = message, snackbarAction = null) }
    }

    fun setViewMode(mode: DamViewMode) {
        update { it.copy(viewMode = mode, isCompareMode = mode == DamViewMode.COMPARE) }
    }
}
