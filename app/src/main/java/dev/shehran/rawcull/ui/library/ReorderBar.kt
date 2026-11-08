package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/**
 * R7 D7: enters reorder mode in a collection in custom order. With filters hiding photos it stays
 * on screen, dimmed, and a tap says why it can't start.
 */
@Composable
fun ReorderButton(filtersHide: Boolean, onReorder: () -> Unit) {
    IconButton(onClick = onReorder, modifier = Modifier.testTag("library_reorder_button")) {
        Icon(
            Icons.Default.SwapHoriz,
            contentDescription = if (filtersHide) "Reorder (clear filters first)" else "Reorder",
            tint = if (filtersHide) TextMuted else Color.White,
        )
    }
}

/** Shown in place of the title row while reordering. */
@Composable
fun ReorderBar(selectedCount: Int, onClearSelection: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("reorder_bar"),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = Dimens.TouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedCount > 0) {
                IconButton(onClick = onClearSelection, modifier = Modifier.testTag("reorder_clear_selection")) {
                    Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = Color.White)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = if (selectedCount > 0) "Reorder · $selectedCount selected" else "Reorder",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Tap to select, long-press and drag to move. Alt+← → moves the selection.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onDone, modifier = Modifier.testTag("reorder_done")) {
                Text("Done", color = PrimaryCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}
