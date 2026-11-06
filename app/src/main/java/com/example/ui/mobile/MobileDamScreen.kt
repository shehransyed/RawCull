package com.example.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ColorLabel
import com.example.data.model.CullStatus
import com.example.ui.DamUiState
import com.example.ui.DamViewMode
import com.example.ui.RawCullViewModel
import com.example.ui.components.CompareLoupeView
import com.example.ui.components.CullingActionBar
import com.example.ui.components.ExifInspectorPanel
import com.example.ui.components.FilmstripBar
import com.example.ui.components.GridViewGallery
import com.example.ui.components.HistogramView
import com.example.ui.components.TopDamBar
import com.example.ui.components.ZoomableLoupeView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileDamScreen(
    uiState: DamUiState,
    viewModel: RawCullViewModel,
    modifier: Modifier = Modifier
) {
    var showExifSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090D))
            .testTag("mobile_dam_screen")
    ) {
        // Top Toolbar
        TopDamBar(
            uiState = uiState,
            onSourceFolderPicked = viewModel::onSourceFolderSelected,
            onLoadSampleShoot = viewModel::loadSampleShoot,
            onSetViewMode = viewModel::setViewMode,
            onUpdateFilter = viewModel::updateFilter,
            onOpenExportDialog = viewModel::openExportDialog,
            onToggleMultiSelect = viewModel::toggleMultiSelectMode
        )

        // Middle Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (uiState.viewMode) {
                DamViewMode.LOUPE -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ZoomableLoupeView(
                                photo = uiState.currentPhoto,
                                onNext = viewModel::nextPhoto,
                                onPrevious = viewModel::previousPhoto,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Floating Info button for EXIF sheet
                            IconButton(
                                onClick = { showExifSheet = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp)
                                    .testTag("mobile_exif_button")
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "EXIF Info", tint = Color.White)
                            }
                        }

                        // Docked Culling Bar
                        CullingActionBar(
                            cullStatus = uiState.currentPhoto?.cullStatus ?: CullStatus.UNFLAGGED,
                            rating = uiState.currentPhoto?.rating ?: 0,
                            colorLabel = uiState.currentPhoto?.colorLabel ?: ColorLabel.NONE,
                            onTogglePick = viewModel::togglePick,
                            onToggleReject = viewModel::toggleReject,
                            onSetRating = viewModel::setRating,
                            onSetColorLabel = viewModel::setColorLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                DamViewMode.COMPARE -> {
                    CompareLoupeView(
                        photoA = uiState.currentPhoto,
                        photoB = uiState.comparePhoto,
                        onPickA = { viewModel.setCullStatus(CullStatus.PICK) },
                        onPickB = {
                            uiState.comparePhoto?.let {
                                viewModel.setCompareIndex(uiState.selectedIndex)
                                viewModel.selectPhotoIndex(uiState.compareIndex)
                                viewModel.setCullStatus(CullStatus.PICK)
                            }
                        },
                        onRejectA = { viewModel.setCullStatus(CullStatus.REJECT) },
                        onRejectB = {
                            uiState.comparePhoto?.let {
                                viewModel.setCompareIndex(uiState.selectedIndex)
                                viewModel.selectPhotoIndex(uiState.compareIndex)
                                viewModel.setCullStatus(CullStatus.REJECT)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                DamViewMode.GRID -> {
                    GridViewGallery(
                        photos = uiState.filteredPhotos,
                        selectedIndex = uiState.selectedIndex,
                        selectedIds = uiState.selectedPhotoIds,
                        isMultiSelectMode = uiState.isMultiSelectMode,
                        onSelectIndex = {
                            viewModel.selectPhotoIndex(it)
                            viewModel.setViewMode(DamViewMode.LOUPE)
                        },
                        onToggleSelectId = viewModel::togglePhotoSelection,
                        onBatchPick = viewModel::batchPickSelected,
                        onBatchReject = viewModel::batchRejectSelected,
                        onOpenExport = viewModel::openExportDialog,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Bottom Filmstrip
        if (uiState.viewMode != DamViewMode.GRID) {
            FilmstripBar(
                photos = uiState.filteredPhotos,
                selectedIndex = uiState.selectedIndex,
                selectedPhotoIds = uiState.selectedPhotoIds,
                isMultiSelectMode = uiState.isMultiSelectMode,
                onSelectIndex = viewModel::selectPhotoIndex,
                onToggleSelectPhoto = viewModel::togglePhotoSelection
            )
        }
    }

    // Modal Bottom Sheet for EXIF & Histogram on mobile
    if (showExifSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExifSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF131821)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                HistogramView(histogram = uiState.currentHistogram)
                ExifInspectorPanel(photo = uiState.currentPhoto, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
