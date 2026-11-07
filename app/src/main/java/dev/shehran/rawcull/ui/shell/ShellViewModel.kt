package dev.shehran.rawcull.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.model.ImportJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** The little state every screen shares: the import running in the background, if any. */
class ShellViewModel(jobs: ImportJobDao) : ViewModel() {
    val activeJob: StateFlow<ImportJob?> = jobs.observeActiveJob()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** A job a notification asked to show, until the shell has opened it. */
    val pendingJobId = MutableStateFlow<Long?>(null)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RawCullApp
                ShellViewModel(app.container.database.importJobDao())
            }
        }
    }
}
