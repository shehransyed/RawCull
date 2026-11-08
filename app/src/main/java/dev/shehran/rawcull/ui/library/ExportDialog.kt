package dev.shehran.rawcull.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.export.ExportNotifications
import dev.shehran.rawcull.data.export.ExportStatus
import dev.shehran.rawcull.library.ExportContent
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.ErrorText
import dev.shehran.rawcull.ui.theme.TextSecondary

/** The export's dialog and its summary, wherever the Library is on screen. */
@Composable
fun ExportOverlays(viewModel: ExportViewModel) {
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    dialog?.let { ExportDialog(it, viewModel) }
    (status as? ExportStatus.Finished)?.let { ExportSummaryDialog(it, onDismiss = viewModel::dismissSummary) }
}

/** R7 D9, D10: what to export, then a folder. */
@Composable
fun ExportDialog(state: ExportDialogState, viewModel: ExportViewModel) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.start(it.toString()) }
    }
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Export \"${state.collectionName}\" in order") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("export_dialog")) {
                Text("In ${state.sort.label.lowercase()}, named 001_, 002_, …", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                ChoiceRow("Originals: RAW, companion and sidecar", state.content == ExportContent.ORIGINALS, "export_originals") {
                    viewModel.setContent(ExportContent.ORIGINALS)
                }
                ChoiceRow("JPEGs only", state.content == ExportContent.JPEGS_ONLY, "export_jpegs") {
                    viewModel.setContent(ExportContent.JPEGS_ONLY)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.TouchTarget)
                        .toggleable(state.keepOrder, role = Role.Switch, onValueChange = viewModel::setKeepOrder)
                        .testTag("export_keep_order"),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep the order in other apps")
                        Text(
                            "Copies get capture times 10 s apart. The library's files don't change.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = state.keepOrder, onCheckedChange = null)
                }
                state.shownIds?.let { shown ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.TouchTarget)
                            .toggleable(state.onlyShown, role = Role.Checkbox, onValueChange = viewModel::setOnlyShown)
                            .testTag("export_only_shown"),
                    ) {
                        Checkbox(checked = state.onlyShown, onCheckedChange = null)
                        Text("Only the ${shown.size} shown", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                state.refusal?.let { Text(it, color = ErrorText, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.testTag("export_choose_folder")) {
                Text("Choose folder…")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.TouchTarget)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A thin row under the top bar while an export runs. */
@Composable
fun ExportProgressRow(status: ExportStatus.Running, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("export_progress")) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (status.total > 0) "Exporting ${status.done} of ${status.total} · ${status.collectionName}" else "Preparing the export…",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel, modifier = Modifier.testTag("export_cancel")) { Text("Cancel") }
            }
            if (status.total > 0) {
                LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            }
        }
    }
}

/** D11: exported, skipped and failed, with *Open folder* when an app can show it. */
@Composable
fun ExportSummaryDialog(status: ExportStatus.Finished, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val summary = status.summary
    val folder = remember(status.treeUri) { folderIntent(status.treeUri) }
    val canOpen = remember(folder) { context.packageManager.resolveActivity(folder, 0) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                (if (summary.cancelled) "Export cancelled: " else "Exported ") + ExportNotifications.photos(summary.exported),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("export_summary")) {
                ExportNotifications.summaryLine(summary).takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (summary.skipped.isNotEmpty()) NameList("Skipped, no JPEG:", summary.skipped)
                if (summary.failed.isNotEmpty()) NameList("Not written:", summary.failed)
                if (summary.cancelled) Text("Files already written stay in the folder.", color = TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("export_summary_ok")) { Text("OK") } },
        dismissButton = if (canOpen) {
            {
                TextButton(onClick = { open(context, folder) }, modifier = Modifier.testTag("export_open_folder")) { Text("Open folder") }
            }
        } else {
            null
        },
    )
}

@Composable
private fun NameList(title: String, names: List<String>) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    val shown = names.take(MAX_NAMES)
    Text(shown.joinToString("\n") + if (names.size > shown.size) "\n…and ${names.size - shown.size} more" else "", color = TextSecondary)
}

private fun folderIntent(treeUri: String): Intent {
    val tree = Uri.parse(treeUri)
    val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Unit // It was there when the dialog opened; nothing else to offer
    }
}

private const val MAX_NAMES = 20
