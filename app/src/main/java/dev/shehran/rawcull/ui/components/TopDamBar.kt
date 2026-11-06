package dev.shehran.rawcull.ui.components

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.CullFilterMode
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.ui.DamUiState
import dev.shehran.rawcull.ui.DamViewMode

@Composable
fun TopDamBar(
    uiState: DamUiState,
    onSourceFolderPicked: (Uri) -> Unit,
    onLoadSampleShoot: () -> Unit,
    onSetViewMode: (DamViewMode) -> Unit,
    onUpdateFilter: ((FilterOptions) -> FilterOptions) -> Unit,
    onOpenExportDialog: () -> Unit,
    onToggleMultiSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSourceFolderPicked(uri)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("top_dam_bar"),
        color = Color(0xFF0C1016),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // Row 1: App Brand, Source/OTG Folder info, View modes, and Batch Export
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Brand + Source folder picker
                Row(verticalAlignment = Alignment.CenterVertically) {
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

                    Column {
                        Text(
                            text = "RAWCULL",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )

                        // Source Folder button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { folderPickerLauncher.launch(null) }
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
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // "Demo Shoot" button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E2632))
                            .clickable { onLoadSampleShoot() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("sample_shoot_button")
                    ) {
                        Text("Demo Shoot", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }

                // Center/Right View Mode Switcher & Export Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // View Mode Group
                    Surface(
                        color = Color(0xFF161E28),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(2.dp)) {
                            ViewModeButton(
                                icon = Icons.Default.Image,
                                label = "Loupe",
                                selected = uiState.viewMode == DamViewMode.LOUPE,
                                onClick = { onSetViewMode(DamViewMode.LOUPE) },
                                tag = "view_mode_loupe"
                            )
                            ViewModeButton(
                                icon = Icons.Default.Compare,
                                label = "Compare",
                                selected = uiState.viewMode == DamViewMode.COMPARE,
                                onClick = { onSetViewMode(DamViewMode.COMPARE) },
                                tag = "view_mode_compare"
                            )
                            ViewModeButton(
                                icon = Icons.Default.GridView,
                                label = "Grid",
                                selected = uiState.viewMode == DamViewMode.GRID,
                                onClick = { onSetViewMode(DamViewMode.GRID) },
                                tag = "view_mode_grid"
                            )
                        }
                    }

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
                                text = if (uiState.isMultiSelectMode) "Select (${uiState.selectedPhotoIds.size})" else "Select",
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

                // RAW+JPG Stacking filter
                if (uiState.rawAndJpegCount > 0) {
                    DamFilterChip(
                        label = "RAW+JPG (${uiState.rawAndJpegCount})",
                        selected = uiState.filterOptions.pairFilter == PairMode.RAW_AND_RASTER,
                        activeColor = Color(0xFFA7F3D0),
                        onClick = {
                            val current = uiState.filterOptions.pairFilter
                            onUpdateFilter { it.copy(pairFilter = if (current == PairMode.RAW_AND_RASTER) null else PairMode.RAW_AND_RASTER) }
                        }
                    )
                }

                // Format filters
                DamFilterChip(
                    label = "Sony ARW",
                    selected = uiState.filterOptions.formatFilter == PhotoFormat.ARW,
                    activeColor = Color(0xFFF97316),
                    onClick = {
                        val current = uiState.filterOptions.formatFilter
                        onUpdateFilter { it.copy(formatFilter = if (current == PhotoFormat.ARW) null else PhotoFormat.ARW) }
                    }
                )
                DamFilterChip(
                    label = "Adobe DNG",
                    selected = uiState.filterOptions.formatFilter == PhotoFormat.DNG,
                    activeColor = Color(0xFF06B6D4),
                    onClick = {
                        val current = uiState.filterOptions.formatFilter
                        onUpdateFilter { it.copy(formatFilter = if (current == PhotoFormat.DNG) null else PhotoFormat.DNG) }
                    }
                )

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
    }
}

@Composable
private fun ViewModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
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
