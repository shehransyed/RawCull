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

private val ErrorRed = Color(0xFFEF4444)

/** Whether the current photo's edits really are in its sidecar. */
@Composable
fun SyncChip(
    status: XmpSyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (background, text) = when (status) {
        XmpSyncStatus.SYNCED -> Color(0xCC064E3B) to Color(0xFFA7F3D0)
        XmpSyncStatus.PENDING, XmpSyncStatus.WRITING -> Color(0xCC0C4A6E) to Color(0xFF38BDF8)
        XmpSyncStatus.ERROR -> Color(0xCC7F1D1D) to Color(0xFFFECACA)
        XmpSyncStatus.READ_ONLY -> Color(0xCC334155) to Color(0xFFCBD5E1)
    }
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
            .background(ErrorRed)
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
                    Text("Replace sidecar", color = ErrorRed)
                }
            }
        },
    )
}
