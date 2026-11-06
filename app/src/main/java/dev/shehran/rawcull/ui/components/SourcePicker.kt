package dev.shehran.rawcull.ui.components

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Entry points for choosing a source, shared by the Welcome screen and the top bar. */
@Stable
class SourcePicker internal constructor(
    /** True when there is no removable volume to offer, so the card action opens the generic picker. */
    val noCardDetected: Boolean,
    val openCard: () -> Unit,
    val pickFolder: () -> Unit,
)

private fun removableVolumes(context: Context): List<StorageVolume> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
    val storage = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return storage.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
}

/**
 * @param initialUri where the generic picker starts, usually the last source.
 * @param storageEpoch changes when storage may have changed, which re-reads the volume list.
 */
@Composable
fun rememberSourcePicker(
    initialUri: Uri?,
    storageEpoch: Int,
    onPicked: (Uri) -> Unit,
): SourcePicker {
    val context = LocalContext.current
    val volumes = remember(storageEpoch) { removableVolumes(context) }
    var showChooser by remember { mutableStateOf(false) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val volumeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(onPicked)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun openVolume(volume: StorageVolume) = volumeLauncher.launch(volume.createOpenDocumentTreeIntent())

    if (showChooser && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Choose a drive") },
            text = {
                Column {
                    volumes.forEach { volume ->
                        Text(
                            text = volume.getDescription(context),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChooser = false
                                    openVolume(volume)
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showChooser = false }) { Text("Cancel") } },
        )
    }

    val belowVolumeApi = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    return SourcePicker(
        noCardDetected = !belowVolumeApi && volumes.isEmpty(),
        openCard = {
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || volumes.isEmpty() -> treeLauncher.launch(null)
                volumes.size == 1 -> openVolume(volumes.single())
                else -> showChooser = true
            }
        },
        pickFolder = { treeLauncher.launch(initialUri) },
    )
}
