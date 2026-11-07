package dev.shehran.rawcull.ui.importer

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.importer.FailedFile
import dev.shehran.rawcull.importer.ImportReport
import dev.shehran.rawcull.ui.theme.AmberRating
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Step 3 of the wizard: the running copy, then its summary. */
@Composable
fun ImportProgressScreen(
    viewModel: ImportProgressViewModel,
    onCullInLibrary: (ImportJob) -> Unit,
    onNewImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("import_progress_screen")) {
        val job = state.job
        val report = state.report
        if (job != null && report != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WizardHeader(step = 3)
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        job.status.isActive -> Running(job, report, state, viewModel::cancel)
                        job.status.isResumable -> Stopped(job, report, viewModel::resume, viewModel::cancel)
                        else -> Finished(job, report, viewModel::retryFailed, onCullInLibrary, onNewImport)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Running(job: ImportJob, report: ImportReport, state: ImportProgressState, onCancel: () -> Unit) {
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }

    Headline("Importing from ${job.sourceLabel}")
    LinearProgressIndicator(
        progress = { job.doneBytes.toFloat() / job.totalBytes.coerceAtLeast(1L) },
        modifier = Modifier.fillMaxWidth().testTag("import_progress_bar"),
    )
    Text(
        text = "${job.doneFiles} of ${job.totalFiles} files · " +
            "${Formatter.formatShortFileSize(context, job.doneBytes)} of ${Formatter.formatShortFileSize(context, job.totalBytes)}",
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("import_progress_counts"),
    )
    val rate = state.bytesPerSecond?.let { "${Formatter.formatShortFileSize(context, it)}/s" }
    val eta = state.etaSeconds?.let { "${DateUtils.formatElapsedTime(it)} left" }
    Text(listOfNotNull(report.currentFile, rate, eta).joinToString(" · "), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    CreatedFolders(report)
    OutlinedButton(
        onClick = { confirming = true },
        modifier = Modifier.heightIn(min = 48.dp).testTag("import_cancel_button"),
    ) { Text("Cancel") }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Cancel this import?") },
            text = { Text("Files already copied stay in the library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onCancel()
                    },
                    modifier = Modifier.testTag("import_cancel_confirm"),
                ) { Text("Cancel import") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun Stopped(job: ImportJob, report: ImportReport, onResume: () -> Unit, onCancel: () -> Unit) {
    val sourceLost = job.status == ImportJobStatus.SOURCE_LOST
    Headline(if (sourceLost) "Card disconnected" else "Import paused")
    Text(
        text = if (sourceLost) {
            "${job.doneFiles} of ${job.totalFiles} files are safely in the library. Put the card back and resume to copy the rest."
        } else {
            "${job.doneFiles} of ${job.totalFiles} files are safely in the library. Check the destination is connected, then resume."
        },
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    CreatedFolders(report)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp).testTag("import_resume_button")) { Text("Resume") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("import_abandon_button")) { Text("Cancel") }
    }
}

@Composable
private fun Finished(
    job: ImportJob,
    report: ImportReport,
    onRetry: () -> Unit,
    onCullInLibrary: (ImportJob) -> Unit,
    onNewImport: () -> Unit,
) {
    val context = LocalContext.current
    Headline(
        when (job.status) {
            ImportJobStatus.CANCELLED -> "Import cancelled"
            ImportJobStatus.COMPLETED_WITH_ERRORS -> "Import finished with errors"
            else -> "Import complete"
        },
    )

    SummaryRow("Copied", "${job.doneFiles} files · ${Formatter.formatShortFileSize(context, job.doneBytes)}", "summary_copied")
    SummaryRow("Skipped as already imported", job.skippedFiles.toString(), "summary_skipped")
    SummaryRow("Failed", job.failedFiles.toString(), "summary_failed")
    if (job.deleteSourceAfter) SummaryRow("Deleted from card", report.deletedFromCard.toString(), "summary_deleted")
    report.elapsedMillis?.let { elapsed ->
        SummaryRow("Time", DateUtils.formatElapsedTime(elapsed / MILLIS_PER_SECOND), "summary_time")
    }
    report.averageBytesPerSecond?.let { speed ->
        SummaryRow("Average speed", "${Formatter.formatShortFileSize(context, speed)}/s", "summary_speed")
    }
    CreatedFolders(report)

    if (report.failed.isNotEmpty()) {
        FileList("Failed files", report.failed)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("import_retry_button")) { Text("Retry failed") }
    }
    if (report.deleteFailures.isNotEmpty()) FileList("Still on the card", report.deleteFailures)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (job.doneFiles > 0) {
            Button(
                onClick = { onCullInLibrary(job) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("cull_in_library_button"),
            ) { Text("Cull in Library") }
        }
        OutlinedButton(onClick = onNewImport, modifier = Modifier.heightIn(min = 48.dp).testTag("new_import_button")) {
            Text("New import")
        }
    }
}

@Composable
private fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String) {
    Row(modifier = Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CreatedFolders(report: ImportReport) {
    if (report.createdFolders.isEmpty()) return
    Column {
        Text("Folders", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        report.createdFolders.forEach { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FileList(title: String, files: List<FailedFile>) {
    Column(modifier = Modifier.testTag("import_failed_list")) {
        Text(title, color = AmberRating, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        files.forEach { file ->
            Text("${file.name}: ${file.reason}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L
