package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FlagCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.RawCullTheme

/**
 * The contextual row shown in place of the title row while photos are selected. The filter rows
 * stay, so that starting a selection doesn't move the tiles under the finger.
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onEdit: (CullEdit) -> Unit,
    onEditTags: () -> Unit,
    onDeleteRejects: () -> Unit,
    onAddToCollection: () -> Unit,
    onRemoveFromCollection: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("selection_top_bar"),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = Dimens.TouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("selection_close")) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = Color.White)
            }
            Text(
                text = "$count selected",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 8.dp).testTag("selection_count"),
            )
            // Scrolls rather than wraps: a phone has room for about half of these.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(Icons.Default.SelectAll, "Select all", "selection_select_all", Color.White, onSelectAll)
                BarIcon(Icons.Default.Check, "Pick", "selection_pick", RawCullTheme.colors.pick) {
                    onEdit(CullEdit.Flag(CullStatus.PICK, toggle = false))
                }
                BarIcon(Icons.Default.Close, "Reject", "selection_reject", RawCullTheme.colors.reject) {
                    onEdit(CullEdit.Flag(CullStatus.REJECT, toggle = false))
                }
                BarIcon(Icons.Default.FlagCircle, "Unflag", "selection_unflag", Color.White) {
                    onEdit(CullEdit.Flag(CullStatus.UNFLAGGED, toggle = false))
                }
                RatingMenu(onEdit)
                LabelMenu(onEdit)
                BarIcon(Icons.AutoMirrored.Filled.Label, "Tags", "selection_tags", Color.White, onEditTags)
                BarIcon(Icons.Default.Collections, "Add to collection", "selection_add_to_collection", Color.White, onAddToCollection)
                onRemoveFromCollection?.let {
                    BarIcon(Icons.Default.RemoveCircleOutline, "Remove from collection", "selection_remove_from_collection", Color.White, it)
                }
                BarIcon(
                    Icons.Default.DeleteOutline,
                    "Delete rejects in selection",
                    "selection_delete_rejects",
                    RawCullTheme.colors.reject,
                    onDeleteRejects,
                )
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, tag: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

@Composable
private fun RatingMenu(onEdit: (CullEdit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarIcon(Icons.Default.Star, "Rating", "selection_rating", RawCullTheme.colors.star) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (0..MAX_RATING).forEach { stars ->
                DropdownMenuItem(
                    text = { Text(if (stars == 0) "No rating" else "★".repeat(stars)) },
                    onClick = {
                        expanded = false
                        onEdit(CullEdit.Rating(stars, toggle = false))
                    },
                    modifier = Modifier.testTag("selection_rating_$stars"),
                )
            }
        }
    }
}

@Composable
private fun LabelMenu(onEdit: (CullEdit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarIcon(Icons.Default.Circle, "Colour label", "selection_label", RawCullTheme.colors.labelBlue) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ColorLabel.entries.filterNot { it == ColorLabel.NONE }.forEach { label ->
                val color = RawCullTheme.colors.label(label)
                DropdownMenuItem(
                    text = { Text(label.labelName.substringBefore(' ')) },
                    leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(color)) },
                    onClick = {
                        expanded = false
                        onEdit(CullEdit.LabelToggle(label))
                    },
                    modifier = Modifier.testTag("selection_label_${label.name.lowercase()}"),
                )
            }
        }
    }
}

private const val MAX_RATING = 5
