package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.CullStatus
import com.example.data.model.ExportConfig
import com.example.data.model.ExportMode
import com.example.data.model.ExportProgress
import com.example.data.model.IngestFileSelection
import com.example.data.model.RawPhotoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExportDialog(
    photos: List<RawPhotoItem>,
    filteredPhotos: List<RawPhotoItem>,
    selectedIds: Set<String>,
    destinationFolderUri: String?,
    destinationFolderName: String,
    exportProgress: ExportProgress,
    onPickDestinationFolder: (Uri, String) -> Unit,
    onStartExport: (ExportConfig) -> Unit,
    onCancelExport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: "Selected Folder"
            onPickDestinationFolder(uri, name)
        }
    }

    var shootName by remember { mutableStateOf("Pacific_Coast_Shoot") }
    var createDatedFolders by remember { mutableStateOf(true) }
    var fileSelection by remember { mutableStateOf(IngestFileSelection.BOTH_RAW_AND_JPEG) }
    var selectedExportMode by remember {
        mutableStateOf(if (selectedIds.isNotEmpty()) ExportMode.CUSTOM_FILTER else ExportMode.PICKS_ONLY)
    }
    var generateXmp by remember { mutableStateOf(true) }
    var minRatingThreshold by remember { mutableIntStateOf(3) }

    // Count how many files would be exported
    val candidateCount = when (selectedExportMode) {
        ExportMode.PICKS_ONLY -> photos.count { it.cullStatus == CullStatus.PICK }
        ExportMode.PICKS_OR_RATED -> photos.count { it.cullStatus == CullStatus.PICK || it.rating >= minRatingThreshold }
        ExportMode.ALL_FLAGGED -> photos.count { it.cullStatus != CullStatus.UNFLAGGED || it.rating > 0 }
        ExportMode.CUSTOM_FILTER -> if (selectedIds.isNotEmpty()) {
            photos.count { selectedIds.contains(it.id) }
        } else {
            filteredPhotos.size
        }
    }

    // Formatted preview path
    val datePreview = SimpleDateFormat("yyyy/MM-dd", Locale.US).format(Date())
    val cleanShoot = shootName.ifBlank { "Shoot" }.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
    val previewPath = if (createDatedFolders) "$datePreview/$cleanShoot/" else "$cleanShoot/"

    Dialog(onDismissRequest = {
        if (!exportProgress.isExporting) onDismiss()
    }) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .testTag("export_dialog"),
            color = Color(0xFF131821),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RAPID INGEST & EXPORT",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Transfer culled RAW+JPEG pairs with live XMP sidecars",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    if (!exportProgress.isExporting) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp).testTag("close_export_dialog")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (exportProgress.completed) {
                    // Export Completed View
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Ingest Completed Successfully!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val mb = exportProgress.bytesWritten / (1024.0 * 1024.0)
                        Text(
                            text = "Transferred ${exportProgress.totalFiles} files (${String.format(Locale.US, "%.1f MB", mb)}) at ${exportProgress.formattedSpeed} to '${exportProgress.targetDirectoryPath}'",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )

                        if (exportProgress.exportedFileNames.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF090D13))
                                    .padding(8.dp)
                            ) {
                                items(exportProgress.exportedFileNames) { name ->
                                    Text(
                                        text = "✓ $name",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("export_done_button")
                        ) {
                            Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (exportProgress.isExporting) {
                    // Live Export Progress View
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = "Ingesting RAW Files (${exportProgress.currentFileIndex} of ${exportProgress.totalFiles})...",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = exportProgress.currentFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF38BDF8)
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        val progressFraction = if (exportProgress.totalFiles > 0) {
                            exportProgress.currentFileIndex.toFloat() / exportProgress.totalFiles
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF10B981),
                            trackColor = Color(0xFF1E293B)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        val writtenMb = exportProgress.bytesWritten / (1024.0 * 1024.0)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(progressFraction * 100).toInt()}% • ${String.format("%.1f MB written", writtenMb)}",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = exportProgress.formattedSpeed,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = onCancelExport,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("cancel_export_button")
                        ) {
                            Text("Cancel Ingest", color = Color(0xFFEF4444))
                        }
                    }
                } else {
                    // Configuration Form View

                    // 1. Destination Folder Selector
                    Text(
                        text = "1. DESTINATION STORAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                            .clickable { folderPickerLauncher.launch(null) }
                            .testTag("choose_dest_folder_button"),
                        color = Color(0xFF0A0E14)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = if (destinationFolderUri != null) Color(0xFF10B981) else Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (destinationFolderUri != null) destinationFolderName else "Tap to choose destination storage folder...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (destinationFolderUri != null) Color.White else Color(0xFF64748B),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (destinationFolderUri != null) "Ready for direct transfer" else "SD Card OTG or Internal Storage folder",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Shoot Name & Date Structure
                    Text(
                        text = "2. SHOOT NAME & DIRECTORY STRUCTURE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = shootName,
                        onValueChange = { shootName = it },
                        label = { Text("Shoot Name", color = Color(0xFF94A3B8)) },
                        placeholder = { Text("e.g. Coast_Sunset", color = Color(0xFF64748B)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("shoot_name_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090D14))
                            .clickable { createDatedFolders = !createDatedFolders }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = createDatedFolders,
                            onCheckedChange = { createDatedFolders = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF38BDF8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Create dated subfolders: YYYY/MM-DD/$cleanShoot/",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Target: .../$previewPath",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. RAW vs JPEG Selection (as requested by user)
                    Text(
                        text = "3. FILE COMPONENTS TO INGEST",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ExportOptionRow(
                            title = IngestFileSelection.BOTH_RAW_AND_JPEG.label + " - " + IngestFileSelection.BOTH_RAW_AND_JPEG.description,
                            selected = fileSelection == IngestFileSelection.BOTH_RAW_AND_JPEG,
                            onSelect = { fileSelection = IngestFileSelection.BOTH_RAW_AND_JPEG }
                        )

                        ExportOptionRow(
                            title = IngestFileSelection.RAW_ONLY.label + " - " + IngestFileSelection.RAW_ONLY.description,
                            selected = fileSelection == IngestFileSelection.RAW_ONLY,
                            onSelect = { fileSelection = IngestFileSelection.RAW_ONLY }
                        )

                        ExportOptionRow(
                            title = IngestFileSelection.JPEG_ONLY.label + " - " + IngestFileSelection.JPEG_ONLY.description,
                            selected = fileSelection == IngestFileSelection.JPEG_ONLY,
                            onSelect = { fileSelection = IngestFileSelection.JPEG_ONLY }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. Scope Selection
                    Text(
                        text = "4. CULLING SELECTION SCOPE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ExportOptionRow(
                            title = "Picks Only (${photos.count { it.cullStatus == CullStatus.PICK }} assets)",
                            selected = selectedExportMode == ExportMode.PICKS_ONLY,
                            onSelect = { selectedExportMode = ExportMode.PICKS_ONLY }
                        )

                        ExportOptionRow(
                            title = "Picks or Rated ≥ $minRatingThreshold Stars (${photos.count { it.cullStatus == CullStatus.PICK || it.rating >= minRatingThreshold }} assets)",
                            selected = selectedExportMode == ExportMode.PICKS_OR_RATED,
                            onSelect = { selectedExportMode = ExportMode.PICKS_OR_RATED }
                        )

                        ExportOptionRow(
                            title = "All Flagged & Rated (${photos.count { it.cullStatus != CullStatus.UNFLAGGED || it.rating > 0 }} assets)",
                            selected = selectedExportMode == ExportMode.ALL_FLAGGED,
                            onSelect = { selectedExportMode = ExportMode.ALL_FLAGGED }
                        )

                        val selectionLabel = if (selectedIds.isNotEmpty()) {
                            "Explicit Selection (${selectedIds.size} assets)"
                        } else {
                            "Current Filtered View (${filteredPhotos.size} assets)"
                        }
                        ExportOptionRow(
                            title = selectionLabel,
                            selected = selectedExportMode == ExportMode.CUSTOM_FILTER,
                            onSelect = { selectedExportMode = ExportMode.CUSTOM_FILTER }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 5. Adobe XMP Sidecar Checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090D14))
                            .clickable { generateXmp = !generateXmp }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = generateXmp,
                            onCheckedChange = { generateXmp = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF38BDF8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Generate Adobe XMP sidecar files (.xmp)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Embeds ratings, color labels & tags for Snapseed, Lightroom, and RapidRAW",
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Bottom Action
                    Button(
                        onClick = {
                            val targetUri = destinationFolderUri ?: return@Button
                            onStartExport(
                                ExportConfig(
                                    destinationFolderUri = targetUri,
                                    shootName = shootName,
                                    createDatedFolders = createDatedFolders,
                                    fileSelection = fileSelection,
                                    exportMode = selectedExportMode,
                                    minRating = minRatingThreshold,
                                    generateXmpSidecars = generateXmp
                                )
                            )
                        },
                        enabled = destinationFolderUri != null && candidateCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            disabledContainerColor = Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("start_batch_export_button")
                    ) {
                        Text(
                            text = if (destinationFolderUri == null) "Select Destination Storage First"
                            else "Ingest $candidateCount Culled Assets",
                            color = if (destinationFolderUri != null && candidateCount > 0) Color.Black else Color(0xFF64748B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportOptionRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect() }
            .padding(vertical = 4.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF38BDF8),
                unselectedColor = Color(0xFF475569)
            ),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
