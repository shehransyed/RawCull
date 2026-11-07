package dev.shehran.rawcull.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.BuildConfig
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.ui.library.LocationUi
import dev.shehran.rawcull.ui.theme.HairlineWhite
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

private const val LICENCE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.refreshAvailability()
        onPauseOrDispose { }
    }
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    var relinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addLocation)
    }
    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = relinkId
        relinkId = null
        if (uri != null && target != null) viewModel.relinkLocation(target, uri)
    }

    Box(modifier = modifier.fillMaxSize().testTag("settings_screen")) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)

                SectionHeader("LIBRARY")
                uiState.locations.forEach { row ->
                    LocationSettingsRow(
                        row = row,
                        onRename = { viewModel.renameLocation(row.location.id, it) },
                        onRelink = {
                            relinkId = row.location.id
                            relinkLauncher.launch(null)
                        },
                        onRemove = { viewModel.removeLocation(row.location.id) },
                    )
                }
                TextButton(onClick = { addLauncher.launch(null) }, modifier = Modifier.testTag("settings_add_location")) {
                    Text("Add location…", color = PrimaryCyan)
                }

                SectionHeader("IMPORT")
                Text("Verification", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                VerifyOption(
                    title = "Full",
                    detail = "Hashes every file while copying, then reads the copy back. The safe choice.",
                    selected = uiState.importPrefs.verifyMode == VerifyMode.FULL,
                    tag = "verify_full",
                    onClick = { viewModel.setVerifyMode(VerifyMode.FULL) },
                )
                VerifyOption(
                    title = "Quick",
                    detail = "Compares the size and the first 64 KB of each copy. Faster, but can miss damage later in a file.",
                    selected = uiState.importPrefs.verifyMode == VerifyMode.QUICK,
                    tag = "verify_quick",
                    onClick = { viewModel.setVerifyMode(VerifyMode.QUICK) },
                )
                SwitchRow(
                    title = "Delete files from the card after a verified import",
                    detail = "Deletion is permanent; cards have no trash. Only verified files on a detected card are deleted.",
                    checked = uiState.importPrefs.deleteFromCardAfterImport,
                    tag = "delete_from_card_switch",
                    onCheckedChange = viewModel::setDeleteFromCard,
                )
                SwitchRow(
                    title = "Hide already-imported photos",
                    detail = "The starting point of the switch in the import preview.",
                    checked = uiState.importPrefs.hideAlreadyImported,
                    tag = "hide_imported_switch",
                    onCheckedChange = viewModel::setHideAlreadyImported,
                )
                Text(
                    text = "Import Profiles",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Change a profile's folders on the Import screen, then Save.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                uiState.presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onRename = { viewModel.renamePreset(preset, it) },
                        onDelete = { viewModel.deletePreset(preset) },
                    )
                }

                SectionHeader("SIDECARS")
                SwitchRow(
                    title = "Write rejects as rating −1",
                    detail = "Lightroom/Bridge/darktable show rating −1 as Rejected. " +
                        "Turn off to keep stars on rejected photos in other apps.",
                    checked = uiState.rejectAsNegativeRating,
                    tag = "reject_as_negative_rating",
                    onCheckedChange = viewModel::setRejectAsNegativeRating,
                )

                if (BuildConfig.DEBUG) {
                    SectionHeader("DEVELOPER")
                    TextButton(onClick = viewModel::addSyntheticPhotos, modifier = Modifier.testTag("settings_add_synthetic")) {
                        Text("Add 50,000 synthetic photos to the demo library", color = PrimaryCyan)
                    }
                    TextButton(onClick = viewModel::removeSyntheticPhotos, modifier = Modifier.testTag("settings_remove_synthetic")) {
                        Text("Remove the synthetic photos", color = PrimaryCyan)
                    }
                }

                SectionHeader("ABOUT")
                Text("RawCull ${BuildConfig.VERSION_NAME}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENCE_URL))) },
                    modifier = Modifier.testTag("settings_licences"),
                ) { Text("Licences", color = PrimaryCyan) }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(color = HairlineWhite, modifier = Modifier.padding(top = 16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun LocationSettingsRow(row: LocationUi, onRename: (String) -> Unit, onRelink: () -> Unit, onRemove: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val location = row.location

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings_location_${location.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(location.displayName, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (row.isAvailable) "Connected" else "Not connected",
                color = if (row.isAvailable) TextMuted else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename ${location.displayName}", tint = TextSecondary) }
        if (!location.isDemo) TextButton(onClick = onRelink) { Text("Relink", color = PrimaryCyan) }
        IconButton(onClick = { removing = true }) { Icon(Icons.Default.Delete, contentDescription = "Remove ${location.displayName}", tint = TextSecondary) }
    }

    if (renaming) {
        NameDialog(
            title = "Rename location",
            initial = location.displayName,
            onSave = {
                onRename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${location.displayName}?") },
            text = { Text("RawCull forgets this location. Your files and sidecars stay where they are.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = false
                        onRemove()
                    },
                    modifier = Modifier.testTag("settings_remove_confirm"),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VerifyOption(title: String, detail: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun PresetRow(preset: ImportPreset, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_preset_${preset.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(preset.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!preset.isStandard) {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename ${preset.name}", tint = TextSecondary) }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("settings_preset_delete")) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${preset.name}", tint = TextSecondary)
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = "Rename profile",
            initial = preset.name,
            onSave = {
                onRename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
