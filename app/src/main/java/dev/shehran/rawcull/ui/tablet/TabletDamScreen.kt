package dev.shehran.rawcull.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.ui.DamUiState
import dev.shehran.rawcull.ui.DamViewMode
import dev.shehran.rawcull.ui.RawCullViewModel
import dev.shehran.rawcull.ui.components.CompareLoupeView
import dev.shehran.rawcull.ui.components.CullingActionBar
import dev.shehran.rawcull.ui.components.ExifInspectorPanel
import dev.shehran.rawcull.ui.components.FilmstripBar
import dev.shehran.rawcull.ui.components.GridViewGallery
import dev.shehran.rawcull.ui.components.HistogramView
import dev.shehran.rawcull.ui.components.TopDamBar
import dev.shehran.rawcull.ui.components.ZoomableLoupeView

@Composable
fun TabletDamScreen(
    uiState: DamUiState,
    viewModel: RawCullViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090D))
            .testTag("tablet_dam_screen")
    ) {
        // Top App Bar with filters, folder picker, and export
        TopDamBar(
            uiState = uiState,
            onSourceFolderPicked = viewModel::onSourceFolderSelected,
            onLoadSampleShoot = viewModel::loadSampleShoot,
            onSetViewMode = viewModel::setViewMode,
            onUpdateFilter = viewModel::updateFilter,
            onOpenExportDialog = viewModel::openExportDialog,
            onToggleMultiSelect = viewModel::toggleMultiSelectMode
        )

        // Middle Work Area: Main Viewer + Right Inspector
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Main Central View (Loupe, Compare, or Grid)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 8.dp)
            ) {
                when (uiState.viewMode) {
                    DamViewMode.LOUPE -> {
                        ZoomableLoupeView(
                            photo = uiState.currentPhoto,
                            onNext = viewModel::nextPhoto,
                            onPrevious = viewModel::previousPhoto,
                            modifier = Modifier.fillMaxSize()
                        )
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
                            selectedPhotoId = uiState.selectedPhotoId,
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

            // Right Inspector Sidebar (Width 320dp, scrollable)
            Surface(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .testTag("right_inspector_sidebar"),
                color = Color(0xFF0F141B),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Culling & Rating Action Bar
                    CullingActionBar(
                        cullStatus = uiState.currentPhoto?.cullStatus ?: CullStatus.UNFLAGGED,
                        rating = uiState.currentPhoto?.rating ?: 0,
                        colorLabel = uiState.currentPhoto?.colorLabel ?: ColorLabel.NONE,
                        onTogglePick = viewModel::togglePick,
                        onToggleReject = viewModel::toggleReject,
                        onSetRating = viewModel::setRating,
                        onSetColorLabel = viewModel::setColorLabel
                    )

                    // 2. Live RGB & Luminance Histogram
                    HistogramView(
                        histogram = uiState.currentHistogram
                    )

                    // 3. EXIF & Metadata Inspector
                    ExifInspectorPanel(
                        photo = uiState.currentPhoto
                    )

                    // 4. Keyword Tags Panel
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        color = Color(0xFF13181F)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "KEYWORDS & TAGS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )

                                Button(
                                    onClick = viewModel::openTagDialog,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2632)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.padding(end = 4.dp))
                                    Text("Edit Tags", fontSize = 11.sp, color = Color(0xFF38BDF8))
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            val tags = uiState.currentPhoto?.tags ?: emptyList()
                            if (tags.isEmpty()) {
                                Text("No tags assigned", color = Color(0xFF64748B), fontSize = 11.sp)
                            } else {
                                Text(
                                    text = tags.joinToString("  •  "),
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Filmstrip
        FilmstripBar(
            photos = uiState.filteredPhotos,
            selectedPhotoId = uiState.selectedPhotoId,
            selectedPhotoIds = uiState.selectedPhotoIds,
            isMultiSelectMode = uiState.isMultiSelectMode,
            onSelectIndex = viewModel::selectPhotoIndex,
            onToggleSelectPhoto = viewModel::togglePhotoSelection
        )
    }
}
