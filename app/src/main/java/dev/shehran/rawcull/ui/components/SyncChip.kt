package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.data.xmp.XMP_PARSE_ERROR
import dev.shehran.rawcull.ui.theme.RawCullTheme

val XmpSyncStatus.label: String
    get() = when (this) {
        XmpSyncStatus.SYNCED -> "Synced ✓"
        XmpSyncStatus.PENDING, XmpSyncStatus.WRITING -> "Saving…"
        XmpSyncStatus.ERROR -> "Error ⚠"
        XmpSyncStatus.READ_ONLY -> "Read-only 🔒"
    }

/** Only these statuses have something more to say when tapped. */
val XmpSyncStatus.hasDetails: Boolean
    get() = this == XmpSyncStatus.ERROR || this == XmpSyncStatus.READ_ONLY

/** The top bar's counter, or null when there's nothing to report. */
fun syncCounterText(saving: Int, errors: Int): String? {
    val parts = buildList {
        if (saving > 0) add("$saving saving")
        if (errors > 0) add(if (errors == 1) "1 error" else "$errors errors")
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/** Whether the current photo's edits really are in its sidecar. */
@Composable
fun SyncChip(
    status: XmpSyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (background, text) = RawCullTheme.colors.sync(status)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(enabled = status.hasDetails, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("sync_chip")
    ) {
        Text(text = status.label, color = text, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

/** Marks a thumbnail whose sidecar has a problem; other states would only add clutter. */
@Composable
fun SyncErrorDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(RawCullTheme.colors.reject)
            .testTag("sync_error_dot")
    )
}

@Composable
fun SyncDetailsDialog(
    photo: PhotoAsset,
    onRetry: () -> Unit,
    onReplaceSidecar: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (photo.xmpSyncStatus == XmpSyncStatus.READ_ONLY) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Read-only source") },
            text = {
                Text("${photo.fileName} is on a read-only source. Your changes are kept in the app and will be written on export.")
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sidecar not saved") },
        text = { Text("${photo.fileName}: ${photo.xmpError ?: "The sidecar couldn't be written."}") },
        confirmButton = { TextButton(onClick = onRetry) { Text("Retry") } },
        dismissButton = {
            // Replacing only makes sense for a file that can't be read; it would discard a good one.
            if (photo.xmpError == XMP_PARSE_ERROR) {
                TextButton(onClick = onReplaceSidecar, modifier = Modifier.testTag("replace_sidecar_button")) {
                    Text("Replace sidecar", color = RawCullTheme.colors.reject)
                }
            }
        },
    )
}
