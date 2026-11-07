package dev.shehran.rawcull.ui.library

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.CullFilterMode
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.components.formatsPresent
import dev.shehran.rawcull.ui.components.pairChipLabel
import dev.shehran.rawcull.ui.components.syncCounterText
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.theme.AmberRating
import dev.shehran.rawcull.ui.theme.DarkroomSurfaceVariant
import dev.shehran.rawcull.ui.theme.EmeraldPick
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.PrimaryCyanVariant
import dev.shehran.rawcull.ui.theme.RoseReject
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Everything the top bar can ask for. */
class LibraryTopBarActions(
    val onToggleFolders: () -> Unit,
    val onToggleInspector: () -> Unit,
    val onSetViewMode: (DamViewMode) -> Unit,
    val onUpdateFilter: ((FilterOptions) -> FilterOptions) -> Unit,
    val onToggleSyncErrors: () -> Unit,
    val onRescan: () -> Unit,
    val onToggleMultiSelect: () -> Unit,
    val onRetryLocation: () -> Unit,
    val onRelinkLocation: () -> Unit,
)

@Composable
fun LibraryTopBar(
    uiState: LibraryUiState,
    actions: LibraryTopBarActions,
    modifier: Modifier = Modifier,
) {
    val layout = LocalWindowLayout.current
    var showFilters by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val filtersVisible = showFilters ?: !layout.isHeightCompact

    Surface(
        modifier = modifier.fillMaxWidth().testTag("library_top_bar"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onToggleFolders, modifier = Modifier.testTag("library_folders_button")) {
                    Icon(Icons.Default.Menu, contentDescription = "Folders and locations", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = uiState.activeLocation?.displayName.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("library_title"),
                    )
                    Text(
                        text = scopeSubtitle(uiState, showFullPath = layout.showsFolderPane),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SyncCounter(uiState, actions.onToggleSyncErrors)
                ViewModeToggle(uiState.viewMode, showLabels = layout.showsFolderPane, onSetViewMode = actions.onSetViewMode)
                IconButton(
                    onClick = { showFilters = !filtersVisible },
                    modifier = Modifier.testTag("library_filter_button"),
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = "Filters",
                        tint = if (filtersVisible) PrimaryCyan else TextSecondary,
                    )
                }
                if (layout.showsSideInspector) {
                    IconButton(onClick = actions.onToggleInspector, modifier = Modifier.testTag("library_inspector_button")) {
                        Icon(Icons.Default.Info, contentDescription = "Inspector", tint = TextSecondary)
                    }
                }
                OverflowMenu(uiState, actions)
            }

            if (filtersVisible) FilterChipsRow(uiState, actions.onUpdateFilter)
            ScanProgressLine(uiState)
            if (!uiState.isLocationAvailable) {
                LocationUnavailableBanner(actions.onRetryLocation, actions.onRelinkLocation)
            }
        }
    }
}

private fun scopeSubtitle(uiState: LibraryUiState, showFullPath: Boolean): String {
    val scope = uiState.scope
    return if (scope is LibraryScope.Folder && showFullPath) {
        scope.relativeDir.replace("/", " › ")
    } else {
        uiState.scopeTitle
    }
}

/** Shows work in flight; tapping it narrows the view to the errors, which is where action is needed. */
@Composable
private fun SyncCounter(uiState: LibraryUiState, onToggleErrors: () -> Unit) {
    val filtering = uiState.filterOptions.syncErrorsOnly
    val text = if (filtering) "Errors only ✕" else syncCounterText(uiState.savingCount, uiState.syncErrorCount)
    if (text == null) return

    val hasErrors = filtering || uiState.syncErrorCount > 0
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        color = if (hasErrors) Color(0xFFFECACA) else PrimaryCyan,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = hasErrors, onClick = onToggleErrors)
            .minimumInteractiveComponentSize()
            .background(if (hasErrors) Color(0xCC7F1D1D) else DarkroomSurfaceVariant)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("sync_counter"),
    )
}

@Composable
private fun ViewModeToggle(selected: DamViewMode, showLabels: Boolean, onSetViewMode: (DamViewMode) -> Unit) {
    Surface(color = DarkroomSurfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Row {
            ViewModeButton(Icons.Default.GridView, "Grid", showLabels, selected == DamViewMode.GRID, "view_mode_grid") {
                onSetViewMode(DamViewMode.GRID)
            }
            ViewModeButton(Icons.Default.Image, "Loupe", showLabels, selected == DamViewMode.LOUPE, "view_mode_loupe") {
                onSetViewMode(DamViewMode.LOUPE)
            }
            ViewModeButton(Icons.Default.Compare, "Compare", showLabels, selected == DamViewMode.COMPARE, "view_mode_compare") {
                onSetViewMode(DamViewMode.COMPARE)
            }
        }
    }
}

@Composable
private fun ViewModeButton(
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val tint = if (selected) Color.White else TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PrimaryCyanVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        if (showLabel) {
            Spacer(Modifier.width(4.dp))
            Text(label, color = tint, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun OverflowMenu(uiState: LibraryUiState, actions: LibraryTopBarActions) {
    var expanded by remember { mutableStateOf(false) }

    fun choose(action: () -> Unit) {
        expanded = false
        action()
    }

    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("library_overflow_button")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions", tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Rescan") },
                enabled = !uiState.isDemo,
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = { choose(actions.onRescan) },
                modifier = Modifier.testTag("rescan_button"),
            )
            DropdownMenuItem(
                text = {
                    Text(if (uiState.isMultiSelectMode) "Select (${uiState.selectedPhotoIds.size})" else "Select")
                },
                leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                onClick = { choose(actions.onToggleMultiSelect) },
                modifier = Modifier.testTag("multi_select_toggle_button"),
            )
        }
    }
}

@Composable
private fun FilterChipsRow(uiState: LibraryUiState, onUpdateFilter: ((FilterOptions) -> FilterOptions) -> Unit) {
    val filter = uiState.filterOptions
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fun cull(mode: CullFilterMode): () -> Unit = { onUpdateFilter { it.copy(cullFilter = mode) } }

        DamFilterChip("All (${uiState.totalCount})", filter.cullFilter == CullFilterMode.ALL, onClick = cull(CullFilterMode.ALL))
        DamFilterChip(
            "Picks (${uiState.picksCount})",
            filter.cullFilter == CullFilterMode.PICKS_ONLY,
            EmeraldPick,
            cull(CullFilterMode.PICKS_ONLY),
        )
        DamFilterChip(
            "Unflagged (${uiState.unflaggedCount})",
            filter.cullFilter == CullFilterMode.UNFLAGGED_ONLY,
            onClick = cull(CullFilterMode.UNFLAGGED_ONLY),
        )
        DamFilterChip(
            "Rejects (${uiState.rejectsCount})",
            filter.cullFilter == CullFilterMode.REJECTS_ONLY,
            RoseReject,
            cull(CullFilterMode.REJECTS_ONLY),
        )

        ChipDivider()

        pairChipLabel(uiState.photos)?.let { label ->
            DamFilterChip(
                "$label (${uiState.pairedCount})",
                filter.pairFilter == PairMode.RAW_AND_RASTER,
                Color(0xFFA7F3D0),
            ) {
                onUpdateFilter {
                    it.copy(pairFilter = if (it.pairFilter == PairMode.RAW_AND_RASTER) null else PairMode.RAW_AND_RASTER)
                }
            }
        }
        formatsPresent(uiState.photos).forEach { format ->
            DamFilterChip(format.extension, filter.formatFilter == format, formatChipColor(format)) {
                onUpdateFilter { it.copy(formatFilter = if (it.formatFilter == format) null else format) }
            }
        }

        ChipDivider()

        DamFilterChip("★ 5 Only", filter.minRating == 5, AmberRating) {
            onUpdateFilter { it.copy(minRating = if (it.minRating == 5) 0 else 5) }
        }
        DamFilterChip("★ 3+ Stars", filter.minRating == 3, AmberRating) {
            onUpdateFilter { it.copy(minRating = if (it.minRating == 3) 0 else 3) }
        }
    }
}

@Composable
private fun ChipDivider() {
    Box(modifier = Modifier.height(20.dp).width(1.dp).background(Color(0x33FFFFFF)))
}

private fun formatChipColor(format: PhotoFormat): Color = when (format) {
    PhotoFormat.ARW -> Color(0xFFF97316)
    PhotoFormat.DNG -> Color(0xFF06B6D4)
    else -> PrimaryCyan
}

@Composable
private fun DamFilterChip(
    label: String,
    selected: Boolean,
    activeColor: Color = PrimaryCyan,
    onClick: () -> Unit,
) {
    // The visible chip is small, but its touch target is the full 48 dp.
    Box(
        modifier = Modifier.clickable(onClick = onClick).minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) activeColor else Color(0xFFCBD5E1),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) activeColor.copy(alpha = 0.2f) else DarkroomSurfaceVariant)
                .border(1.dp, if (selected) activeColor else Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ScanProgressLine(uiState: LibraryUiState) {
    if (uiState.isScanning) {
        val progress = uiState.scanProgress
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = PrimaryCyan,
                trackColor = DarkroomSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = PrimaryCyan,
                trackColor = DarkroomSurfaceVariant,
            )
        }
    }
    if (uiState.scanStatusText.isNotBlank()) {
        Text(
            text = uiState.scanStatusText,
            color = TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp).testTag("scan_status_text"),
        )
    }
}

@Composable
private fun LocationUnavailableBanner(onRetry: () -> Unit, onRelink: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3B2A0B))
            .padding(start = 14.dp, end = 6.dp)
            .testTag("location_unavailable_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = AmberRating, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Location not connected",
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRelink, modifier = Modifier.testTag("location_relink_button")) {
            Text("Relink", color = AmberRating)
        }
        TextButton(onClick = onRetry, modifier = Modifier.testTag("location_retry_button")) {
            Text("Retry", color = AmberRating, fontWeight = FontWeight.Bold)
        }
    }
}
