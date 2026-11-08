package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.library.CollectionTree

/** New, rename, move and delete for the Collections section (D5). Deleting never touches photos. */
@Composable
fun CollectionDialogs(
    dialog: CollectionDialog?,
    uiState: LibraryUiState,
    actions: CollectionPaneActions,
    onClose: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is CollectionDialog.Create -> NameDialog(
            title = if (dialog.isSet) "New collection set" else "New collection",
            initial = "",
            confirm = "Create",
            onClose = onClose,
        ) { actions.onCreate(it, dialog.parentId, dialog.isSet) }
        is CollectionDialog.Rename -> NameDialog(
            title = "Rename",
            initial = dialog.collection.name,
            confirm = "Rename",
            onClose = onClose,
        ) { actions.onRename(dialog.collection.id, it) }
        is CollectionDialog.Move -> MoveDialog(dialog.collection, uiState.collections, actions, onClose)
        is CollectionDialog.Delete -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Delete \"${dialog.collection.name}\"?") },
            text = {
                Text(
                    if (dialog.collection.isSet) {
                        "The set and everything inside it go. Your photos are not touched."
                    } else {
                        "The collection goes. Your photos are not touched."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDelete(dialog.collection.id)
                        onClose()
                    },
                    modifier = Modifier.testTag("collection_delete_confirm"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onClose: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().testTag("collection_name_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDone(name)
                    onClose()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("collection_name_confirm"),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun MoveDialog(
    moving: PhotoCollection,
    all: List<PhotoCollection>,
    actions: CollectionPaneActions,
    onClose: () -> Unit,
) {
    val tree = remember(all) { CollectionTree(all) }
    // Only places that would accept it: not itself or below, and no name clash.
    val targets = remember(tree) {
        buildList<Pair<Long?, String>> {
            if (tree.effectiveParent(moving) != null && tree.moveRefusal(moving.id, null) == null) add(null to "Top level")
            tree.rows().forEach { row ->
                val set = row.collection
                if (set.isSet && set.id != tree.effectiveParent(moving) && tree.moveRefusal(moving.id, set.id) == null) {
                    add(set.id to (tree.ancestors(set.id).map { it.name } + set.name).joinToString(" / "))
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Move \"${moving.name}\" to…") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (targets.isEmpty()) Text("There is nowhere else it can go.", style = MaterialTheme.typography.bodyMedium)
                targets.forEach { (id, label) ->
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                actions.onMove(moving.id, id)
                                onClose()
                            }
                            .padding(vertical = 12.dp)
                            .testTag("collection_move_to_${id ?: "top"}"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
