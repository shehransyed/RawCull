package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.library.CollectionRow
import dev.shehran.rawcull.library.CollectionTree
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** What the Collections section of the pane can ask for (D5). */
class CollectionPaneActions(
    val onOpen: (Long) -> Unit,
    val onToggleSet: (Long) -> Unit,
    val onCreate: (name: String, parentId: Long?, isSet: Boolean) -> Unit,
    val onRename: (Long, String) -> Unit,
    val onMove: (Long, Long?) -> Unit,
    val onDelete: (Long) -> Unit,
    val onSetTarget: (Long?) -> Unit,
)

/** A dialog the section opens; the pane keeps the one that is showing. */
sealed interface CollectionDialog {
    data class Create(val parentId: Long?, val isSet: Boolean) : CollectionDialog
    data class Rename(val collection: PhotoCollection) : CollectionDialog
    data class Move(val collection: PhotoCollection) : CollectionDialog
    data class Delete(val collection: PhotoCollection) : CollectionDialog
}

/** The *Collections* section under the folder tree: the same list in the side pane and in the drawer. */
fun LazyListScope.collectionsSection(
    uiState: LibraryUiState,
    actions: CollectionPaneActions,
    request: (CollectionDialog) -> Unit,
) {
    if (uiState.activeLocation == null) return
    item(key = "collections_title") { CollectionsHeader(request) }
    val rows = CollectionTree(uiState.collections).rows(uiState.expandedCollectionSets)
    if (rows.isEmpty()) {
        item(key = "collections_empty") {
            Text(
                "No collections yet",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("collections_empty"),
            )
        }
    }
    items(rows, key = { "collection_${it.collection.id}" }) { row ->
        CollectionPaneRow(
            row = row,
            expanded = row.collection.id in uiState.expandedCollectionSets,
            selected = (uiState.scope as? LibraryScope.Collection)?.id == row.collection.id,
            isTarget = row.collection.id == uiState.targetCollectionId,
            actions = actions,
            request = request,
        )
    }
}

@Composable
private fun CollectionsHeader(request: (CollectionDialog) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "COLLECTIONS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("collections_add")) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New collection or set", tint = TextSecondary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("New collection") },
                    onClick = {
                        menu = false
                        request(CollectionDialog.Create(null, isSet = false))
                    },
                    modifier = Modifier.testTag("collections_new_collection"),
                )
                DropdownMenuItem(
                    text = { Text("New set") },
                    onClick = {
                        menu = false
                        request(CollectionDialog.Create(null, isSet = true))
                    },
                    modifier = Modifier.testTag("collections_new_set"),
                )
            }
        }
    }
}

@Composable
private fun CollectionPaneRow(
    row: CollectionRow,
    expanded: Boolean,
    selected: Boolean,
    isTarget: Boolean,
    actions: CollectionPaneActions,
    request: (CollectionDialog) -> Unit,
) {
    val collection = row.collection
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) PrimaryCyan.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { if (collection.isSet) actions.onToggleSet(collection.id) else actions.onOpen(collection.id) }
            .padding(start = (8 + row.depth * INDENT_DP).dp)
            .testTag("collection_row_${collection.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (collection.isSet) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse ${collection.name}" else "Expand ${collection.name}",
                    tint = TextSecondary,
                )
            } else {
                Icon(
                    Icons.Default.Collections,
                    contentDescription = null,
                    tint = if (selected) PrimaryCyan else TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (collection.isSet) {
            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
        Text(
            text = collection.name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (isTarget) {
            Icon(
                Icons.Default.Star,
                contentDescription = "Target collection",
                tint = PrimaryCyan,
                modifier = Modifier.size(16.dp).testTag("collection_target_${collection.id}"),
            )
        }
        CollectionMenu(collection, isTarget, actions, request)
    }
}

@Composable
private fun CollectionMenu(
    collection: PhotoCollection,
    isTarget: Boolean,
    actions: CollectionPaneActions,
    request: (CollectionDialog) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun Item(text: String, tag: String, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(text) },
        onClick = {
            open = false
            onClick()
        },
        modifier = Modifier.testTag(tag),
    )
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("collection_menu_${collection.id}")) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${collection.name}", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (collection.isSet) {
                Item("New collection here", "collection_new_in_${collection.id}") {
                    request(CollectionDialog.Create(collection.id, isSet = false))
                }
                Item("New set here", "collection_new_set_in_${collection.id}") {
                    request(CollectionDialog.Create(collection.id, isSet = true))
                }
            } else if (isTarget) {
                Item("Clear target", "collection_clear_target") { actions.onSetTarget(null) }
            } else {
                Item("Set as target", "collection_set_target_${collection.id}") { actions.onSetTarget(collection.id) }
            }
            Item("Rename…", "collection_rename_${collection.id}") { request(CollectionDialog.Rename(collection)) }
            Item("Move…", "collection_move_${collection.id}") { request(CollectionDialog.Move(collection)) }
            Item("Delete…", "collection_delete_${collection.id}") { request(CollectionDialog.Delete(collection)) }
        }
    }
}

private const val INDENT_DP = 14
