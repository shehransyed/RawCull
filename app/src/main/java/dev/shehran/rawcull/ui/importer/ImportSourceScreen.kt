package dev.shehran.rawcull.ui.importer

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.data.importer.CardVolume
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.importer.ImportDestination
import dev.shehran.rawcull.importer.PathTemplate
import dev.shehran.rawcull.ui.components.StorageChangeEffect
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.theme.AmberRating
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Step 1 of the wizard: what to import from, and where it goes. */
@Composable
fun ImportSourceScreen(viewModel: ImportViewModel, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = LocalWindowLayout.current
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleStartEffect(Unit) {
        viewModel.onStorageChanged()
        onStopOrDispose { }
    }
    StorageChangeEffect(onChanged = viewModel::onStorageChanged)
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    // Survives the trip to the picker, which can take the process with it.
    var pickingCardUuid by rememberSaveable { mutableStateOf<String?>(null) }
    val cardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val card = uiState.cards.firstOrNull { it.uuid == pickingCardUuid }
        pickingCardUuid = null
        val uri = result.data?.data
        if (card != null && uri != null) viewModel.onCardPicked(card, uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderPicked)
    }

    uiState.pendingCardPick?.let { card ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCardHint,
            title = { Text(card.description) },
            text = { Text("Choose the DCIM folder (Android doesn't allow the card's top folder).") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissCardHint()
                        val intent = viewModel.pickerIntentFor(card)
                        if (intent != null) {
                            pickingCardUuid = card.uuid
                            cardLauncher.launch(intent)
                        } else {
                            folderLauncher.launch(null)
                        }
                    },
                    modifier = Modifier.testTag("card_hint_continue"),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissCardHint) { Text("Cancel") } },
        )
    }

    Box(modifier = modifier.fillMaxSize().testTag("import_source_screen")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            WizardHeader(step = 1)
            val sourcePanel = @Composable { panelModifier: Modifier ->
                SourcePanel(
                    uiState = uiState,
                    onCardTapped = viewModel::onCardTapped,
                    onChooseFolder = { folderLauncher.launch(null) },
                    modifier = panelModifier,
                )
            }
            val destinationPanel = @Composable { panelModifier: Modifier ->
                DestinationPanel(uiState = uiState, viewModel = viewModel, modifier = panelModifier)
            }
            if (layout.showsSideBySide) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    sourcePanel(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    destinationPanel(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    sourcePanel(Modifier.fillMaxWidth())
                    destinationPanel(Modifier.fillMaxWidth())
                }
            }
            WizardActionBar(
                nextLabel = "Next",
                nextEnabled = uiState.canContinue,
                onNext = onNext,
                nextTag = "import_next_button",
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun WizardHeader(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("Source", "Preview", "Import").forEachIndexed { index, title ->
            val current = index + 1 == step
            Text(
                text = "${index + 1}  $title",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) PrimaryCyan else TextMuted,
            )
        }
    }
}

/** The sticky bar at the bottom of each step. */
@Composable
internal fun WizardActionBar(
    nextLabel: String,
    nextEnabled: Boolean,
    onNext: () -> Unit,
    nextTag: String,
    onBack: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("import_back_button")) {
                    Text("Back")
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onNext,
                enabled = nextEnabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag(nextTag),
            ) { Text(nextLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SourcePanel(
    uiState: ImportUiState,
    onCardTapped: (CardVolume) -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle("SOURCE")
        if (uiState.cards.isEmpty()) {
            Text("No card detected. Plug one in, or choose a folder.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        uiState.cards.forEach { card ->
            CardTile(card, selected = uiState.source?.volumeUuid == card.uuid && card.uuid != null, onClick = { onCardTapped(card) })
        }
        OutlinedButton(
            onClick = onChooseFolder,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import_choose_folder_button"),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose a folder…", color = PrimaryCyan)
        }
        SourceStatus(uiState)
    }
}

@Composable
private fun CardTile(card: CardVolume, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (selected) PrimaryCyan else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("card_tile"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (card.isUsb) Icons.Default.Usb else Icons.Default.SdCard,
            contentDescription = null,
            tint = PrimaryCyan,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(card.description, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val free = card.freeBytes
            val total = card.totalBytes
            if (free != null && total != null) {
                Text(
                    text = "${Formatter.formatShortFileSize(context, free)} free of ${Formatter.formatShortFileSize(context, total)}",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(
            text = if (card.isUsb) "USB" else "SD CARD",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = PrimaryCyan,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = PrimaryCyan, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SourceStatus(uiState: ImportUiState) {
    uiState.source?.let { source ->
        Text(
            text = "Source: ${source.label}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp).testTag("import_source_label"),
        )
    }
}

@Composable
private fun DestinationPanel(uiState: ImportUiState, viewModel: ImportViewModel, modifier: Modifier = Modifier) {
    val draft = uiState.draft
    var savingAs by rememberSaveable { mutableStateOf(false) }
    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addLocation)
    }
    var relinkTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = relinkTargetId
        relinkTargetId = null
        if (uri != null && target != null) viewModel.relinkLocation(target, uri)
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle("DESTINATION")

        val presetLabel = uiState.selectedPreset?.name.orEmpty() + if (uiState.isDraftModified) " (modified)" else ""
        SelectField(label = "Import Profile", value = presetLabel, tag = "preset_field") { close ->
            uiState.presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = {
                        viewModel.selectPreset(preset.id)
                        close()
                    },
                )
            }
        }
        if (uiState.isDraftModified) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::saveDraft, modifier = Modifier.testTag("preset_save")) { Text("Save") }
                TextButton(onClick = { savingAs = true }, modifier = Modifier.testTag("preset_save_as")) { Text("Save as new…") }
            }
        }

        SelectField(
            label = "Location",
            value = uiState.destination?.displayName ?: "Choose a location",
            tag = "location_field",
        ) { close ->
            uiState.destinationLocations.forEach { row ->
                DropdownMenuItem(
                    text = {
                        Text(if (row.isAvailable) row.location.displayName else "${row.location.displayName} (not connected)")
                    },
                    trailingIcon = if (row.isAvailable) {
                        null
                    } else {
                        {
                            TextButton(onClick = {
                                relinkTargetId = row.location.id
                                close()
                                relinkLauncher.launch(null)
                            }) { Text("Relink") }
                        }
                    },
                    enabled = row.isAvailable,
                    onClick = {
                        viewModel.updateDraft { it.copy(locationId = row.location.id) }
                        close()
                    },
                )
            }
            if (uiState.destinationLocations.isNotEmpty()) HorizontalDivider(color = Color(0x22FFFFFF))
            DropdownMenuItem(
                text = { Text("Add location…") },
                onClick = {
                    close()
                    addLauncher.launch(null)
                },
                modifier = Modifier.testTag("location_add"),
            )
        }
        if (!uiState.hasDestination) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("no_location_warning")) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AmberRating, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (uiState.destination == null) "Choose where photos should go." else "That location isn't connected.",
                    color = AmberRating,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        StructureEditor(draft, viewModel::updateDraft)
        PathPreview(uiState)
    }

    if (savingAs) {
        SaveAsDialog(
            onSave = {
                viewModel.saveDraftAs(it)
                savingAs = false
            },
            onDismiss = { savingAs = false },
        )
    }
}

@Composable
private fun StructureEditor(draft: ImportPreset, onEdit: ((ImportPreset) -> ImportPreset) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            StructureMode.DATE to "Date",
            StructureMode.CUSTOM to "Custom",
            StructureMode.PROJECT to "Project",
            StructureMode.FLAT to "Flat",
        ).forEach { (mode, label) ->
            FilterChip(
                selected = draft.structureMode == mode,
                onClick = { onEdit { it.copy(structureMode = mode) } },
                label = { Text(label) },
                modifier = Modifier.testTag("structure_${mode.name.lowercase()}"),
            )
        }
    }

    when (draft.structureMode) {
        StructureMode.DATE -> Column {
            ImportPreset.DATE_PATTERNS.forEach { pattern ->
                val selected = draft.datePattern == pattern
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onEdit { it.copy(datePattern = pattern) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = PathTemplate.sanitize(pattern.replace("{year}", "YYYY").replace("{month}", "MM").replace("{day}", "DD")),
                        color = if (selected) PrimaryCyan else Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = PrimaryCyan, modifier = Modifier.size(18.dp))
                }
            }
        }
        StructureMode.CUSTOM -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = draft.customPattern,
                onValueChange = { value -> onEdit { it.copy(customPattern = value) } },
                label = { Text("Pattern") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("custom_pattern_field"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PathTemplate.TOKEN_YEAR, PathTemplate.TOKEN_MONTH, PathTemplate.TOKEN_DAY).forEach { token ->
                    TextButton(onClick = { onEdit { it.copy(customPattern = it.customPattern + token) } }) { Text(token) }
                }
            }
        }
        StructureMode.PROJECT -> OutlinedTextField(
            value = draft.projectName,
            onValueChange = { value -> onEdit { it.copy(projectName = value) } },
            label = { Text("Project folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("project_name_field"),
        )
        StructureMode.FLAT -> Text("Everything goes straight into the location.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PathPreview(uiState: ImportUiState) {
    val now = remember { System.currentTimeMillis() }
    Column(modifier = Modifier.testTag("path_preview")) {
        Text("Files will go to", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        Text(
            text = ImportDestination.previewPath(uiState.destination, uiState.draft, now),
            color = PrimaryCyan,
            style = MaterialTheme.typography.bodyMedium,
        )
        uiState.firstPhotoDate?.let { date ->
            Text(
                text = "First photo: " + ImportDestination.previewPath(uiState.destination, uiState.draft, date),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A labelled field that opens a menu. */
@Composable
private fun SelectField(label: String, value: String, tag: String, items: @Composable (close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                Text(value, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

@Composable
private fun SaveAsDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save import profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.testTag("preset_name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("preset_name_save")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
