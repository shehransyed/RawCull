package dev.shehran.rawcull.device.robots

import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.AppContainer
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.importer.ImportSessionState
import dev.shehran.rawcull.ui.importer.ImportViewModel
import kotlinx.coroutines.runBlocking

/** Steps on the Import tab and the import wizard behind it (R8.5). */
class ImportRobot(private val rule: MainRule) {

    val viewModel: ImportViewModel by lazy { rule.activityViewModel(ImportViewModel.Factory) }

    val container: AppContainer
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RawCullApp).container

    fun openTab() = apply {
        rule.onNodeWithTag(NAV_TAB, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    /** What the folder picker would hand the app. */
    fun pickSource(treeUri: Uri) = apply {
        rule.runOnUiThread { viewModel.onFolderPicked(treeUri) }
    }

    /** Makes [treeUri] the destination Location and waits until the wizard accepts it. */
    fun addDestination(treeUri: Uri) = apply {
        rule.runOnUiThread { viewModel.addLocation(treeUri) }
        rule.waitUntil(DEFAULT_TIMEOUT_MS) { viewModel.uiState.value.hasDestination }
    }

    /** Waits until the source is scanned and its metadata read. */
    fun awaitReady(timeoutMs: Long = 120_000) = apply {
        rule.waitUntil(timeoutMs) {
            val s = viewModel.uiState.value
            s.session is ImportSessionState.Ready && s.candidates.isNotEmpty()
        }
    }

    fun candidates(): List<ImportCandidate> = viewModel.uiState.value.candidates

    fun awaitPreviewTiles(count: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = apply {
        rule.waitUntil(timeoutMs) { rule.countNodes(hasTestTag(PREVIEW_TILE)) == count }
    }

    fun structure(mode: StructureMode, project: String = "") = apply {
        rule.runOnUiThread { viewModel.updateDraft { it.copy(structureMode = mode, projectName = project) } }
        rule.waitUntil(DEFAULT_TIMEOUT_MS) { viewModel.uiState.value.draft.structureMode == mode }
    }

    fun draft(): ImportPreset = viewModel.uiState.value.draft

    /** Starts the import the way the Start button does; returns the job's id. */
    fun start(): Long {
        val before = latestJobId()
        rule.runOnUiThread { viewModel.startImport() }
        rule.waitUntil(DEFAULT_TIMEOUT_MS) { viewModel.uiState.value.message != null || latestJobId() != before }
        val id = latestJobId()
        check(id != before) { "Import didn't start: ${viewModel.uiState.value.message}" }
        return id
    }

    /** Waits until the job is no longer queued or running; returns it. */
    fun awaitJob(id: Long, timeoutMs: Long = 180_000): ImportJob {
        var job: ImportJob? = null
        rule.waitUntil(timeoutMs) {
            job = runBlocking { container.database.importJobDao().getJob(id) }
            job?.status?.let { !it.isActive } == true
        }
        return checkNotNull(job)
    }

    /** Scan, start and wait for the end, from a fresh pick of [source]. */
    fun importAll(source: Uri): ImportJob {
        pickSource(source).awaitReady()
        return awaitJob(start())
    }

    fun latestJobId(): Long = count("SELECT COALESCE(MAX(id), 0) FROM import_jobs")

    fun count(sql: String): Long = container.database.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }

    fun tap(tag: String) = apply {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    fun assertCompleted(job: ImportJob): ImportJob = job.also {
        check(it.status == ImportJobStatus.COMPLETED) { "Import ended ${it.status}, failed ${it.failedFiles}" }
    }

    companion object {
        const val NAV_TAB = "nav_import"
        const val PREVIEW_TILE = "preview_tile"
        const val CULL_IN_LIBRARY = "cull_in_library_button"
        const val OPEN_COLLECTION = "open_collection_button"
    }
}
