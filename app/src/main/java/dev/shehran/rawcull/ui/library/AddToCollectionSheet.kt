package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.library.CollectionPicker
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/**
 * R7 D6: picks the collection for the selection (else the current photo). The search field is also
 * the name of a new collection, so there is nothing else to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(
    uiState: LibraryUiState,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val choices = remember(uiState.collections, query, uiState.targetCollectionId) {
        CollectionPicker.choices(uiState.collections, query, uiState.targetCollectionId)
    }
    val canCreate = remember(uiState.collections, query) { CollectionPicker.canCreate(uiState.collections, query) }
    val count = if (uiState.isSelecting) uiState.selectedPhotoIds.size else 1

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .dismissOnEscape(takeFocus = false, onDismiss = onDismiss)
                .testTag("add_to_collection_sheet"),
        ) {
            Text(
                text = if (count == 1) "Add 1 photo to a collection" else "Add $count photos to a collection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search, or name a new collection") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("add_to_collection_query"),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (canCreate) {
                    item {
                        ChoiceRow(
                            icon = Icons.Default.Add,
                            title = "New collection \"${query.trim()}\"",
                            detail = null,
                            tag = "add_to_collection_new",
                        ) { onCreate(query.trim()) }
                    }
                }
                items(choices, key = { it.collection.id }) { choice ->
                    ChoiceRow(
                        icon = if (choice.collection.id == uiState.targetCollectionId) Icons.Default.Star else Icons.Default.Collections,
                        title = choice.path,
                        detail = if (choice.collection.id == uiState.targetCollectionId) "target" else null,
                        tag = "add_to_collection_${choice.collection.id}",
                    ) { onPick(choice.collection.id) }
                }
                if (choices.isEmpty() && !canCreate) {
                    item {
                        Text(
                            if (uiState.collections.none { !it.isSet }) "No collections yet. Type a name to make one." else "Nothing matches.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String?,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        detail?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.labelMedium) }
    }
}
