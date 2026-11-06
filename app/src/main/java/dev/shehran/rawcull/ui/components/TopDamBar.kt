package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.CullFilterMode
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.ui.DamUiState
import dev.shehran.rawcull.ui.DamViewMode

/** Everything the source dropdown and the disconnected banner can do. */
data class SourceActions(
    val onOpenRecent: (String) -> Unit,
    val onOpenCard: () -> Unit,
    val onPickFolder: () -> Unit,
    val onLoadDemo: () -> Unit,
    val onRescan: () -> Unit,
    val onCloseSource: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
)

private val CompactWidth = 600.dp

@Composable
fun TopDamBar(
    uiState: DamUiState,
    sourceActions: SourceActions,
    onSetViewMode: (DamViewMode) -> Unit,
    onUpdateFilter: ((FilterOptions) -> FilterOptions) -> Unit,
    onOpenExportDialog: () -> Unit,
    onToggleMultiSelect: () -> Unit,
    onToggleSyncErrors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("top_dam_bar"),
        color = Color(0xFF0C1016),
        tonalElevation = 4.dp
    ) {
        BoxWithConstraints {
        val isCompact = maxWidth < CompactWidth
        Column {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // Row 1: App Brand, Source/OTG Folder info, View modes, and Batch Export
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Brand + source dropdown; takes the space left over, so it is what ellipsizes
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0284C7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraRoll,
                            contentDescription = "RawCull",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "RAWCULL",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                        SourceDropdown(uiState = uiState, actions = sourceActions)
                    }

                    SyncCounter(uiState = uiState, onToggleErrors = onToggleSyncErrors)
                }

                // View Mode Group
                Surface(
                    color = Color(0xFF161E28),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        ViewModeButton(
                            icon = Icons.Default.Image,
                            label = "Loupe",
                            showLabel = !isCompact,
                            selected = uiState.viewMode == DamViewMode.LOUPE,
                            onClick = { onSetViewMode(DamViewMode.LOUPE) },
                            tag = "view_mode_loupe"
                        )
                        ViewModeButton(
                            icon = Icons.Default.Compare,
                            label = "Compare",
                            showLabel = !isCompact,
                            selected = uiState.viewMode == DamViewMode.COMPARE,
                            onClick = { onSetViewMode(DamViewMode.COMPARE) },
                            tag = "view_mode_compare"
                        )
                        ViewModeButton(
                            icon = Icons.Default.GridView,
                            label = "Grid",
                            showLabel = !isCompact,
                            selected = uiState.viewMode == DamViewMode.GRID,
                            onClick = { onSetViewMode(DamViewMode.GRID) },
                            tag = "view_mode_grid"
                        )
                    }
                }

                if (isCompact) {
                    CompactOverflowMenu(
                        uiState = uiState,
                        onToggleMultiSelect = onToggleMultiSelect,
                        onOpenExportDialog = onOpenExportDialog,
                    )
                } else {
                    // Multi-select toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (uiState.isMultiSelectMode) Color(0xFF0369A1) else Color(0xFF1E2632))
                            .clickable { onToggleMultiSelect() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("multi_select_toggle_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = selectLabel(uiState),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Batch Export Culled Files Button (Prominent)
                    Button(
                        onClick = onOpenExportDialog,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("open_export_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Ingest Culled Files",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ingest Culled (${uiState.picksCount})",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Filter chips (Picks, Unflagged, Rejects, Stars, Format ARW/DNG, Stacks)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cull filters
                DamFilterChip(
                    label = "All (${uiState.totalCount})",
                    selected = uiState.filterOptions.cullFilter == CullFilterMode.ALL,
                    onClick = { onUpdateFilter { it.copy(cullFilter = CullFilterMode.ALL) } }
                )
                DamFilterChip(
                    label = "Picks (${uiState.picksCount})",
                    selected = uiState.filterOptions.cullFilter == CullFilterMode.PICKS_ONLY,
                    activeColor = Color(0xFF10B981),
                    onClick = { onUpdateFilter { it.copy(cullFilter = CullFilterMode.PICKS_ONLY) } }
                )
                DamFilterChip(
                    label = "Unflagged (${uiState.unflaggedCount})",
                    selected = uiState.filterOptions.cullFilter == CullFilterMode.UNFLAGGED_ONLY,
                    onClick = { onUpdateFilter { it.copy(cullFilter = CullFilterMode.UNFLAGGED_ONLY) } }
                )
                DamFilterChip(
                    label = "Rejects (${uiState.rejectsCount})",
                    selected = uiState.filterOptions.cullFilter == CullFilterMode.REJECTS_ONLY,
                    activeColor = Color(0xFFEF4444),
                    onClick = { onUpdateFilter { it.copy(cullFilter = CullFilterMode.REJECTS_ONLY) } }
                )

                Box(modifier = Modifier.height(20.dp).width(1.dp).background(Color(0x33FFFFFF)))

                // Paired files filter
                pairChipLabel(uiState.photos)?.let { label ->
                    DamFilterChip(
                        label = "$label (${uiState.pairedCount})",
                        selected = uiState.filterOptions.pairFilter == PairMode.RAW_AND_RASTER,
                        activeColor = Color(0xFFA7F3D0),
                        onClick = {
                            val current = uiState.filterOptions.pairFilter
                            onUpdateFilter { it.copy(pairFilter = if (current == PairMode.RAW_AND_RASTER) null else PairMode.RAW_AND_RASTER) }
                        }
                    )
                }

                // Format filters, for the formats in this catalog
                formatsPresent(uiState.photos).forEach { format ->
                    DamFilterChip(
                        label = format.extension,
                        selected = uiState.filterOptions.formatFilter == format,
                        activeColor = formatChipColor(format),
                        onClick = {
                            val current = uiState.filterOptions.formatFilter
                            onUpdateFilter { it.copy(formatFilter = if (current == format) null else format) }
                        }
                    )
                }

                Box(modifier = Modifier.height(20.dp).width(1.dp).background(Color(0x33FFFFFF)))

                // Star ratings filter
                DamFilterChip(
                    label = "★ 5 Only",
                    selected = uiState.filterOptions.minRating == 5,
                    activeColor = Color(0xFFFBBF24),
                    onClick = {
                        val current = uiState.filterOptions.minRating
                        onUpdateFilter { it.copy(minRating = if (current == 5) 0 else 5) }
                    }
                )
                DamFilterChip(
                    label = "★ 3+ Stars",
                    selected = uiState.filterOptions.minRating == 3,
                    activeColor = Color(0xFFFBBF24),
                    onClick = {
                        val current = uiState.filterOptions.minRating
                        onUpdateFilter { it.copy(minRating = if (current == 3) 0 else 3) }
                    }
                )
            }
        }

        ScanProgressLine(uiState)
        if (!uiState.isSourceAvailable) {
            SourceUnavailableBanner(onRetry = sourceActions.onRetry)
        }
        }
        }
    }
}

private fun selectLabel(uiState: DamUiState): String =
    if (uiState.isMultiSelectMode) "Select (${uiState.selectedPhotoIds.size})" else "Select"

private fun formatChipColor(format: PhotoFormat): Color = when (format) {
    PhotoFormat.ARW -> Color(0xFFF97316)
    PhotoFormat.DNG -> Color(0xFF06B6D4)
    else -> Color(0xFF38BDF8)
}

/** Shows work in flight; tapping it narrows the view to the errors, which is where action is needed. */
@Composable
private fun SyncCounter(uiState: DamUiState, onToggleErrors: () -> Unit) {
    val filtering = uiState.filterOptions.syncErrorsOnly
    val text = if (filtering) "Errors only ✕" else syncCounterText(uiState.savingCount, uiState.syncErrorCount)
    if (text == null) return

    val hasErrors = filtering || uiState.syncErrorCount > 0
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        color = if (hasErrors) Color(0xFFFECACA) else Color(0xFF38BDF8),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (hasErrors) Color(0xCC7F1D1D) else Color(0xFF161E28))
            .clickable(enabled = hasErrors, onClick = onToggleErrors)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("sync_counter")
    )
}

@Composable
private fun SourceDropdown(uiState: DamUiState, actions: SourceActions) {
    var expanded by remember { mutableStateOf(false) }

    fun choose(action: () -> Unit) {
        expanded = false
        action()
    }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(vertical = 1.dp)
                .testTag("source_folder_button")
        ) {
            Icon(
                imageVector = Icons.Default.Usb,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = uiState.sourceFolderName,
                fontSize = 11.sp,
                color = Color(0xFF38BDF8),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Source menu",
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(14.dp)
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            uiState.recentSources.forEach { recent ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (recent.isAvailable) recent.displayName else "${recent.displayName} (not connected)",
                            color = if (recent.isAvailable) Color.White else Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { choose { actions.onOpenRecent(recent.uri) } }
                )
            }
            if (uiState.recentSources.isNotEmpty()) HorizontalDivider(color = Color(0x22FFFFFF))
            DropdownMenuItem(text = { Text("Open SD card…") }, onClick = { choose(actions.onOpenCard) })
            DropdownMenuItem(text = { Text("Pick folder…") }, onClick = { choose(actions.onPickFolder) })
            DropdownMenuItem(
                text = { Text("Demo shoot") },
                onClick = { choose(actions.onLoadDemo) },
                modifier = Modifier.testTag("sample_shoot_button")
            )
            HorizontalDivider(color = Color(0x22FFFFFF))
            DropdownMenuItem(
                text = { Text("Rescan") },
                enabled = !uiState.isDemoSource,
                onClick = { choose(actions.onRescan) }
            )
            DropdownMenuItem(
                text = { Text("Close source") },
                onClick = { choose(actions.onCloseSource) },
                modifier = Modifier.testTag("close_source_button")
            )
            HorizontalDivider(color = Color(0x22FFFFFF))
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { choose(actions.onOpenSettings) },
                modifier = Modifier.testTag("settings_button")
            )
        }
    }
}

@Composable
private fun CompactOverflowMenu(
    uiState: DamUiState,
    onToggleMultiSelect: () -> Unit,
    onOpenExportDialog: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(36.dp).testTag("top_bar_overflow_button")
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions", tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(selectLabel(uiState)) },
                leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                onClick = {
                    expanded = false
                    onToggleMultiSelect()
                },
                modifier = Modifier.testTag("multi_select_toggle_button")
            )
            DropdownMenuItem(
                text = { Text("Ingest Culled (${uiState.picksCount})") },
                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenExportDialog()
                },
                modifier = Modifier.testTag("open_export_dialog_button")
            )
        }
    }
}

@Composable
private fun ScanProgressLine(uiState: DamUiState) {
    if (uiState.isScanning) {
        val progress = uiState.scanProgress
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFF38BDF8),
                trackColor = Color(0xFF161E28),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFF38BDF8),
                trackColor = Color(0xFF161E28),
            )
        }
    }
    if (uiState.scanStatusText.isNotBlank()) {
        Text(
            text = uiState.scanStatusText,
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 2.dp)
                .testTag("scan_status_text")
        )
    }
}

@Composable
private fun SourceUnavailableBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3B2A0B))
            .padding(start = 14.dp, end = 6.dp)
            .testTag("source_unavailable_banner"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFBBF24),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Card disconnected. Reconnect it and tap Retry",
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag("source_retry_button")) {
            Text("Retry", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ViewModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF0284C7) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color.White else Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp)
            )
            if (showLabel) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    color = if (selected) Color.White else Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun DamFilterChip(
    label: String,
    selected: Boolean,
    activeColor: Color = Color(0xFF38BDF8),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) activeColor.copy(alpha = 0.2f) else Color(0xFF161E27))
            .border(
                width = 1.dp,
                color = if (selected) activeColor else Color(0x22FFFFFF),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) activeColor else Color(0xFFCBD5E1),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
