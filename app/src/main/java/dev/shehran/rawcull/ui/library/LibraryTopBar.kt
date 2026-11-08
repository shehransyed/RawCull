package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.components.syncCounterText
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.theme.DarkroomSurfaceVariant
import dev.shehran.rawcull.ui.theme.ErrorContainer
import dev.shehran.rawcull.ui.theme.ErrorText
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.PrimaryCyanVariant
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.ui.theme.WarningContainer
import dev.shehran.rawcull.ui.theme.WarningText

/** Everything the top bar can ask for. */
class LibraryTopBarActions(
    val onToggleFolders: () -> Unit,
    val onToggleInspector: () -> Unit,
    val onSetViewMode: (DamViewMode) -> Unit,
    val onToggleFilterBar: () -> Unit,
    val onToggleSyncErrors: () -> Unit,
    val onShowShortcuts: () -> Unit,
    val onFullWindow: () -> Unit,
    val onSlideshow: () -> Unit,
    val onDeleteRejects: () -> Unit,
    val onAddToCollection: () -> Unit,
    val onRemoveFromCollection: () -> Unit,
    val onRescan: () -> Unit,
    val onRetryLocation: () -> Unit,
    val onRelinkLocation: () -> Unit,
)

@Composable
fun LibraryTopBar(
    uiState: LibraryUiState,
    filterBarVisible: Boolean,
    actions: LibraryTopBarActions,
    filterActions: FilterActions,
    focusSearch: Boolean,
    onOpenFilterSheet: () -> Unit,
    modifier: Modifier = Modifier,
    selectionRow: (@Composable () -> Unit)? = null,
) {
    val layout = LocalWindowLayout.current

    Surface(
        modifier = modifier.fillMaxWidth().testTag("library_top_bar"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            if (selectionRow != null) selectionRow() else Row(
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
                        text = listOfNotNull(scopeSubtitle(uiState, showFullPath = layout.showsFolderPane), filterCountText(uiState))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("library_subtitle"),
                    )
                }
                SyncCounter(uiState, actions.onToggleSyncErrors)
                ViewModeToggle(uiState.viewMode, showLabels = layout.showsFolderPane, onSetViewMode = actions.onSetViewMode)
                IconButton(
                    onClick = actions.onToggleFilterBar,
                    modifier = Modifier.testTag("library_filter_button"),
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = "Filters",
                        tint = if (filterBarVisible) PrimaryCyan else TextSecondary,
                    )
                }
                if (layout.showsSideInspector) {
                    IconButton(onClick = actions.onToggleInspector, modifier = Modifier.testTag("library_inspector_button")) {
                        Icon(Icons.Default.Info, contentDescription = "Inspector", tint = TextSecondary)
                    }
                }
                OverflowMenu(uiState, actions)
            }

            if (filterBarVisible) {
                if (layout.showsFilterBar) {
                    FilterBar(uiState, filterActions, focusSearch)
                } else {
                    ActiveFilterRow(uiState, filterActions, onOpenFilterSheet)
                }
            }
            ScanProgressLine(uiState)
            if (!uiState.isLocationAvailable) {
                LocationUnavailableBanner(actions.onRetryLocation, actions.onRelinkLocation)
            }
        }
    }
}

/** "137 of 842", only when the filters are hiding something. */
private fun filterCountText(uiState: LibraryUiState): String? {
    val shown = uiState.filteredPhotos.size
    val total = uiState.totalCount
    return if (shown < total) "$shown of $total" else null
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
        color = if (hasErrors) ErrorText else PrimaryCyan,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = hasErrors, onClick = onToggleErrors)
            .minimumInteractiveComponentSize()
            .background(if (hasErrors) ErrorContainer else DarkroomSurfaceVariant)
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
                text = { Text("Delete rejects…") },
                enabled = uiState.rejectsCount > 0 && uiState.isLocationAvailable,
                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                onClick = { choose(actions.onDeleteRejects) },
                modifier = Modifier.testTag("delete_rejects_button"),
            )
            DropdownMenuItem(
                text = { Text("Add to collection…") },
                enabled = uiState.currentPhoto != null,
                leadingIcon = { Icon(Icons.Default.Collections, contentDescription = null) },
                onClick = { choose(actions.onAddToCollection) },
                modifier = Modifier.testTag("add_to_collection_menu_item"),
            )
            if (uiState.activeCollection != null) {
                DropdownMenuItem(
                    text = { Text("Remove from collection") },
                    enabled = uiState.currentPhoto != null,
                    leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = null) },
                    onClick = { choose(actions.onRemoveFromCollection) },
                    modifier = Modifier.testTag("remove_from_collection_menu_item"),
                )
            }
            DropdownMenuItem(
                text = { Text("Full window") },
                enabled = uiState.filteredPhotos.isNotEmpty(),
                leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                onClick = { choose(actions.onFullWindow) },
                modifier = Modifier.testTag("full_window_menu_item"),
            )
            DropdownMenuItem(
                text = { Text("Slideshow") },
                enabled = uiState.filteredPhotos.isNotEmpty(),
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = { choose(actions.onSlideshow) },
                modifier = Modifier.testTag("slideshow_menu_item"),
            )
            DropdownMenuItem(
                text = { Text("Keyboard shortcuts") },
                leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                onClick = { choose(actions.onShowShortcuts) },
                modifier = Modifier.testTag("keyboard_shortcuts_button"),
            )
        }
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
            .background(WarningContainer)
            .padding(start = 14.dp, end = 6.dp)
            .testTag("location_unavailable_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = RawCullTheme.colors.star, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Location not connected",
            color = WarningText,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRelink, modifier = Modifier.testTag("location_relink_button")) {
            Text("Relink", color = RawCullTheme.colors.star)
        }
        TextButton(onClick = onRetry, modifier = Modifier.testTag("location_retry_button")) {
            Text("Retry", color = RawCullTheme.colors.star, fontWeight = FontWeight.Bold)
        }
    }
}
