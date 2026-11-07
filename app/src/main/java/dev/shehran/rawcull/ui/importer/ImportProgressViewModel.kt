package dev.shehran.rawcull.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shehran.rawcull.AppContainer
import dev.shehran.rawcull.data.importer.ImportController
import dev.shehran.rawcull.data.importer.ImportStart
import dev.shehran.rawcull.data.local.ImportJobDao
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.importer.ImportReport
import dev.shehran.rawcull.importer.SpeedMeter
import dev.shehran.rawcull.importer.etaSeconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportProgressState(
    val job: ImportJob? = null,
    val report: ImportReport? = null,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val message: String? = null,
)

/** Reads everything from Room, so it works after process death and straight from the notification. */
class ImportProgressViewModel(
    private val jobId: Long,
    jobs: ImportJobDao,
    private val controller: ImportController,
    clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImportProgressState())
    val uiState: StateFlow<ImportProgressState> = _uiState.asStateFlow()

    private val speed = SpeedMeter()

    init {
        viewModelScope.launch {
            combine(jobs.observeJob(jobId), jobs.observeItems(jobId)) { job, items ->
                job?.let { it to ImportReport.of(it, items) }
            }.collect { snapshot ->
                val (job, report) = snapshot ?: return@collect
                speed.add(clock(), job.doneBytes)
                val rate = speed.bytesPerSecond.takeIf { job.status.isActive }
                _uiState.update {
                    it.copy(
                        job = job,
                        report = report,
                        bytesPerSecond = rate,
                        etaSeconds = etaSeconds(job.totalBytes - job.doneBytes, rate),
                    )
                }
            }
        }
    }

    fun cancel() {
        viewModelScope.launch { controller.cancel(jobId) }
    }

    fun resume() = react { controller.resume(jobId) }

    fun retryFailed() = react { controller.retryFailed(jobId) }

    private fun react(action: suspend () -> ImportStart) {
        viewModelScope.launch {
            (action() as? ImportStart.Refused)?.let { refused -> _uiState.update { it.copy(message = refused.message) } }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        /** Takes [container] directly: Nav3's entry store has no application in its creation extras. */
        fun factory(container: AppContainer, jobId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImportProgressViewModel(jobId, container.database.importJobDao(), container.importController)
            }
        }
    }
}
