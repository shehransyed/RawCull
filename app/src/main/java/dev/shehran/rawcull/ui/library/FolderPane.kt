package dev.shehran.rawcull.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.library.FolderNode
import dev.shehran.rawcull.library.FolderTree
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

class FolderPaneActions(
    val onSelectLocation: (Long) -> Unit,
    val onRelinkLocation: (Long) -> Unit,
    val onAddLocation: () -> Unit,
    val onSelectScope: (LibraryScope) -> Unit,
    val onShowLatestImport: () -> Unit,
    val onToggleFolder: (String) -> Unit,
)

/** The Location switcher and folder tree: a side pane on wide windows, a drawer on narrow ones. */
@Composable
fun FolderPane(uiState: LibraryUiState, actions: FolderPaneActions, modifier: Modifier = Modifier) {
    val visible = remember(uiState.folderTree, uiState.expandedFolders) {
        FolderTree.visibleRows(uiState.folderTree, uiState.expandedFolders)
    }

    LazyColumn(
        modifier = modifier.fillMaxHeight().testTag("folder_pane"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item { SectionTitle("LOCATIONS") }
        items(uiState.locations, key = { "location_${it.location.id}" }) { row ->
            LocationRow(row, selected = row.location.id == uiState.activeLocation?.id, actions)
        }
        item {
            PaneRow(
                icon = Icons.Default.Add,
                title = "Add location…",
                selected = false,
                onClick = actions.onAddLocation,
                tag = "folder_pane_add_location",
            )
            HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
        }

        if (uiState.activeLocation != null) {
            item {
                PaneRow(
                    icon = Icons.Default.PhotoLibrary,
                    title = "All photos",
                    detail = uiState.locationPhotoCount.toString(),
                    selected = uiState.scope == LibraryScope.All,
                    onClick = { actions.onSelectScope(LibraryScope.All) },
                    tag = "folder_pane_all",
                )
            }
            uiState.latestImport?.let { job ->
                item {
                    PaneRow(
                        icon = Icons.Default.Schedule,
                        title = "Latest Import",
                        subtitle = importDate(job.finishedAt ?: job.createdAt),
                        detail = uiState.latestImportPhotoCount.toString(),
                        selected = uiState.scope is LibraryScope.LatestImport,
                        onClick = actions.onShowLatestImport,
                        tag = "folder_pane_latest_import",
                    )
                }
            }
            if (visible.isNotEmpty()) item { SectionTitle("FOLDERS") }
            items(visible, key = { "folder_${it.node.path}" }) { row ->
                FolderRow(
                    node = row.node,
                    depth = row.depth,
                    isExpanded = row.isExpanded,
                    selected = (uiState.scope as? LibraryScope.Folder)?.relativeDir == row.node.path,
                    onSelect = { actions.onSelectScope(LibraryScope.Folder(row.node.path)) },
                    onToggle = { actions.onToggleFolder(row.node.path) },
                )
            }
        }
    }
}

@Composable
private fun importDate(time: Long): String {
    val context = LocalContext.current
    return remember(time) { DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun LocationRow(row: LocationUi, selected: Boolean, actions: FolderPaneActions) {
    val location = row.location
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = row.isAvailable) { actions.onSelectLocation(location.id) }
            .padding(start = 16.dp, end = 8.dp)
            .testTag("location_row_${location.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = location.displayName,
                color = if (row.isAvailable) Color.White else TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.isAvailable) {
                Text("not connected", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Active", tint = PrimaryCyan, modifier = Modifier.size(18.dp))
        if (!row.isAvailable) {
            TextButton(onClick = { actions.onRelinkLocation(location.id) }) { Text("Relink", color = PrimaryCyan) }
        }
    }
}

@Composable
private fun PaneRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    subtitle: String? = null,
    detail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) PrimaryCyan.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) PrimaryCyan else TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.labelSmall) }
        }
        detail?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun FolderRow(
    node: FolderNode,
    depth: Int,
    isExpanded: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) PrimaryCyan.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = (4 + depth * INDENT_DP).dp, end = 16.dp)
            .testTag("folder_row_${node.path}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.children.isNotEmpty()) {
            IconButton(onClick = onToggle, modifier = Modifier.testTag("folder_toggle_${node.path}")) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse ${node.name}" else "Expand ${node.name}",
                    tint = TextSecondary,
                )
            }
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = node.name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(node.totalCount.toString(), color = TextMuted, style = MaterialTheme.typography.labelMedium)
    }
}

private const val INDENT_DP = 14
