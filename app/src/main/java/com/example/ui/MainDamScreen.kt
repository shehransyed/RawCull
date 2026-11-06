package com.example.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.components.ExportDialog
import com.example.ui.components.TagManagerDialog
import com.example.ui.mobile.MobileDamScreen
import com.example.ui.tablet.TabletDamScreen

@Composable
fun MainDamScreen(
    viewModel: RawCullViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 700.dp

        if (isTablet) {
            TabletDamScreen(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MobileDamScreen(
                uiState = uiState,
                viewModel = viewModel,
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
    }
}
