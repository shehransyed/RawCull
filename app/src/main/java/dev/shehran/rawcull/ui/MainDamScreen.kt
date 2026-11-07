package dev.shehran.rawcull.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.shehran.rawcull.ui.components.ExportDialog
import dev.shehran.rawcull.ui.components.SettingsSheet
import dev.shehran.rawcull.ui.components.SyncDetailsDialog
import dev.shehran.rawcull.ui.components.TagManagerDialog
import dev.shehran.rawcull.ui.components.WelcomeScreen
import dev.shehran.rawcull.ui.components.rememberSourcePicker
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.mobile.MobileDamScreen
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
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
    LifecycleStartEffect(Unit) {
        viewModel.onAppStart()
        onStopOrDispose { }
    }
    StorageChangeEffect(onChanged = viewModel::refreshSources)

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            // Clearing first would change this effect's key and cancel the snackbar.
            snackbarHostState.showSnackbar(message)
            viewModel.onSnackbarShown()
        }
    }

    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocus.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .focusRequester(keyFocus)
            .focusable()
            // Only this screen listens, so typing anywhere else in the app is left alone.
            .onPreviewKeyEvent { event ->
                val action = LibraryKeys.actionFor(event.key)
                if (event.type == KeyEventType.KeyDown && action != null) {
                    onLibraryKey(action, viewModel)
                    true
                } else {
                    false
                }
            }
    ) {
        val isTablet = LocalWindowLayout.current.showsSideInspector

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

private fun onLibraryKey(action: LibraryKeyAction, viewModel: RawCullViewModel) {
    when (action) {
        LibraryKeyAction.TogglePick -> viewModel.togglePick()
        LibraryKeyAction.ToggleReject -> viewModel.toggleReject()
        LibraryKeyAction.Unflag -> viewModel.setCullStatus(CullStatus.UNFLAGGED)
        is LibraryKeyAction.Rate -> viewModel.setRating(action.stars)
        LibraryKeyAction.Previous -> viewModel.previousPhoto()
        LibraryKeyAction.Next -> viewModel.nextPhoto()
        LibraryKeyAction.ToggleCompare -> viewModel.toggleCompareMode()
        LibraryKeyAction.ShowAllPhotos -> viewModel.setScope(LibraryScope.All)
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
