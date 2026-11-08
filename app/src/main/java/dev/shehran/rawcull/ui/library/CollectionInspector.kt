package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** What the inspector's collections card can ask for (D6). */
class CollectionInspectorActions(
    val onAdd: () -> Unit,
    val onRemove: () -> Unit,
    val onOpen: (Long) -> Unit,
)

fun LibraryViewModel.collectionInspectorActions() = CollectionInspectorActions(
    onAdd = ::openAddToCollection,
    onRemove = ::removeFromCollection,
    onOpen = { setScope(LibraryScope.Collection(it)) },
)

/** The photo's collections as chips (a tap opens one), with *Add*, and *Remove* when a collection is open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsCard(
    collections: List<PhotoCollection>,
    inCollectionView: Boolean,
    actions: CollectionInspectorActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).testTag("collections_card"),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "COLLECTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Row {
                    if (inCollectionView) {
                        TextButton(onClick = actions.onRemove, modifier = Modifier.testTag("inspector_remove_from_collection")) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
                            Text("Remove", fontSize = 11.sp, color = PrimaryCyan, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    TextButton(onClick = actions.onAdd, modifier = Modifier.testTag("inspector_add_to_collection")) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
                        Text("Add", fontSize = 11.sp, color = PrimaryCyan, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (collections.isEmpty()) {
                Text("Not in any collection", color = TextMuted, fontSize = 11.sp)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    collections.forEach { collection ->
                        AssistChip(
                            onClick = { actions.onOpen(collection.id) },
                            label = { Text(collection.name, maxLines = 1) },
                            modifier = Modifier.testTag("collection_chip_${collection.id}"),
                        )
                    }
                }
            }
        }
    }
}
