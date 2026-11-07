package dev.shehran.rawcull.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.shehran.rawcull.library.DeletionPlan
import dev.shehran.rawcull.library.RejectDeletion

/** RawCull's own question, before any system dialog. Permanent deletion gets the destructive look. */
@Composable
fun DeleteRejectsDialog(plan: DeletionPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val permanent = plan.isAllPermanent
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("delete_rejects_dialog"),
        icon = if (permanent) {
            { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        title = { Text(RejectDeletion.title(plan)) },
        text = { Text(RejectDeletion.detail(plan)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (permanent) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.testTag("delete_rejects_confirm"),
            ) {
                Text(if (permanent) "Delete permanently" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete_rejects_cancel")) { Text("Cancel") }
        },
    )
}
