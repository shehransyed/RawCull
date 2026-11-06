package dev.shehran.rawcull.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.shehran.rawcull.ui.components.ExportDialog
import dev.shehran.rawcull.ui.components.SettingsSheet
import dev.shehran.rawcull.ui.components.SyncDetailsDialog
import dev.shehran.rawcull.ui.components.TagManagerDialog
import dev.shehran.rawcull.ui.components.WelcomeScreen
import dev.shehran.rawcull.ui.components.rememberSourcePicker
import dev.shehran.rawcull.ui.mobile.MobileDamScreen
import dev.shehran.rawcull.ui.tablet.TabletDamScreen

@Composable
fun MainDamScreen(
    viewModel: RawCullViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val sourcePicker = rememberSourcePicker(
        initialUri = uiState.recentSources.firstOrNull()?.uri?.let(Uri::parse),
        storageEpoch = uiState.storageEpoch,
        onPicked = viewModel::onSourceFolderSelected,
    )

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSources()
        onPauseOrDispose { }
    }
    StorageChangeEffect(onChanged = viewModel::refreshSources)

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            // Clearing first would change this effect's key and cancel the snackbar.
            snackbarHostState.showSnackbar(message)
            viewModel.onSnackbarShown()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTablet = this.maxWidth >= 700.dp

        when {
            // Avoids flashing Welcome while the last source is being checked.
            uiState.isRestoring -> Box(Modifier.fillMaxSize().background(Color(0xFF07090D)))
            uiState.currentRootUri == null -> WelcomeScreen(
                recents = uiState.recentSources,
                picker = sourcePicker,
                onOpenRecent = viewModel::openRecentSource,
                onLoadDemo = viewModel::loadSampleShoot,
            )
            isTablet -> TabletDamScreen(
                uiState = uiState,
                viewModel = viewModel,
                sourcePicker = sourcePicker,
                modifier = Modifier.fillMaxSize()
            )
            else -> MobileDamScreen(
                uiState = uiState,
                viewModel = viewModel,
                sourcePicker = sourcePicker,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Export Dialog
        if (uiState.showExportDialog) {
            ExportDialog(
                photos = uiState.photos,
                filteredPhotos = uiState.filteredPhotos,
                selectedIds = uiState.selectedPhotoIds,
                destinationFolderUri = uiState.destinationFolderUri,
                destinationFolderName = uiState.destinationFolderName,
                exportProgress = uiState.exportProgress,
                onPickDestinationFolder = viewModel::onDestinationFolderSelected,
                onStartExport = viewModel::startBatchExport,
                onCancelExport = viewModel::cancelExport,
                onDismiss = viewModel::dismissExportDialog
            )
        }

        // Tag Manager Dialog
        if (uiState.showTagDialog) {
            val currentPhoto = uiState.currentPhoto
            TagManagerDialog(
                currentTags = currentPhoto?.tags ?: emptyList(),
                onAddTag = viewModel::addTagToCurrent,
                onRemoveTag = viewModel::removeTagFromCurrent,
                onDismiss = viewModel::dismissTagDialog
            )
        }

        uiState.syncDetailsPhoto?.let { photo ->
            SyncDetailsDialog(
                photo = photo,
                onRetry = { viewModel.retrySidecar(photo.id) },
                onReplaceSidecar = { viewModel.replaceSidecar(photo.id) },
                onDismiss = viewModel::dismissSyncDetails,
            )
        }

        if (uiState.showSettings) {
            SettingsSheet(
                rejectAsNegativeRating = uiState.rejectAsNegativeRating,
                onRejectAsNegativeRatingChange = viewModel::setRejectAsNegativeRating,
                onDismiss = viewModel::dismissSettings,
            )
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Nothing reads the card while idle, so unplugging it has to be noticed from the outside. */
@Composable
private fun StorageChangeEffect(onChanged: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChanged()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}
