package dev.shehran.rawcull.ui.importer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.importer.ImportCollection
import dev.shehran.rawcull.library.CollectionPicker
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextSecondary

/** R7 D8: file the imported photos in a collection too: none, one that exists, or a new name. */
@Composable
fun ImportCollectionRow(uiState: ImportUiState, onChoose: (ImportCollection) -> Unit) {
    if (uiState.destination == null) return
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val choices = remember(uiState.collections) { CollectionPicker.choices(uiState.collections, "", null) }
    val label = when (val choice = uiState.effectiveCollection) {
        ImportCollection.None -> "None"
        is ImportCollection.Existing -> choices.firstOrNull { it.collection.id == choice.id }?.path ?: "None"
        is ImportCollection.New -> "New: ${choice.name}"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Collections, contentDescription = null, tint = TextSecondary)
        Text(
            "Add to collection",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.testTag("import_collection_button")) {
                Text(label, color = PrimaryCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        menu = false
                        onChoose(ImportCollection.None)
                    },
                    modifier = Modifier.testTag("import_collection_none"),
                )
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            menu = false
                            onChoose(ImportCollection.Existing(choice.collection.id))
                        },
                        modifier = Modifier.testTag("import_collection_${choice.collection.id}"),
                    )
                }
                DropdownMenuItem(
                    text = { Text("New collection…") },
                    onClick = {
                        menu = false
                        naming = true
                    },
                    modifier = Modifier.testTag("import_collection_new"),
                )
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf((uiState.collectionChoice as? ImportCollection.New)?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().testTag("import_collection_name"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChoose(ImportCollection.New(name.trim()))
                        naming = false
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.testTag("import_collection_name_confirm"),
                ) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}
