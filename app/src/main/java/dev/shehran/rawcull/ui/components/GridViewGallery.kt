package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.CardSurface
import dev.shehran.rawcull.ui.theme.CardSurfaceFloating
import dev.shehran.rawcull.ui.theme.FormatOrange
import dev.shehran.rawcull.ui.theme.GridBackground
import dev.shehran.rawcull.ui.theme.HairlineWhite
import dev.shehran.rawcull.ui.theme.PairedMint
import dev.shehran.rawcull.ui.theme.PickBorder
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.RejectBorder
import dev.shehran.rawcull.ui.theme.ScrimStronger
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.util.RawPreviewLoader

@Composable
fun GridViewGallery(
    photos: List<PhotoAsset>,
    selectedPhotoId: String?,
    selectedIds: Set<String>,
    isMultiSelectMode: Boolean,
    onSelectIndex: (Int) -> Unit,
    onToggleSelectId: (String) -> Unit,
    onBatchPick: () -> Unit,
    onBatchReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val rows = remember(photos) { buildGridRows(photos) }
    val selectedRow = remember(rows, selectedPhotoId) {
        rows.indexOfFirst { it is GridRow.Photo && it.photo.id == selectedPhotoId }
    }
    // Saved, so rotating keeps the scroll position instead of jumping back to the selection.
    var followedPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedPhotoId, selectedRow) {
        if (selectedRow >= 0 && selectedPhotoId != followedPhotoId) {
            followedPhotoId = selectedPhotoId
            gridState.scrollToItem(selectedRow)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GridBackground)
            .testTag("grid_view_gallery")
    ) {
        if (photos.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No photos to show",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = Dimens.TileMinSize),
                contentPadding = PaddingValues(Dimens.GridPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.TileGap),
                verticalArrangement = Arrangement.spacedBy(Dimens.TileGap),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = rows,
                    key = { it.key },
                    span = { row -> GridItemSpan(if (row is GridRow.Header) maxLineSpan else 1) },
                    contentType = { it::class },
                ) { row ->
                    when (row) {
                        is GridRow.Header -> Text(
                            text = row.title,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).testTag("grid_folder_header"),
                        )
                        is GridRow.Photo -> {
                            val photo = row.photo
                            GridPhotoCard(
                                photo = photo,
                                isCurrent = photo.id == selectedPhotoId,
                                isChecked = selectedIds.contains(photo.id),
                                isMultiSelectMode = isMultiSelectMode,
                                onClick = {
                                    if (isMultiSelectMode) {
                                        onToggleSelectId(photo.id)
                                    } else {
                                        onSelectIndex(row.index)
                                    }
                                },
                                modifier = Modifier.testTag("grid_card_${row.index}")
                            )
                        }
                    }
                }
            }
        }

        // Bottom floating batch actions bar when items are selected
        if (selectedIds.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .testTag("batch_actions_bar"),
                color = CardSurfaceFloating,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${selectedIds.size} selected",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Button(
                        onClick = onBatchPick,
                        colors = ButtonDefaults.buttonColors(containerColor = RawCullTheme.colors.pick),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(36.dp).testTag("batch_pick_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onBatchReject,
                        colors = ButtonDefaults.buttonColors(containerColor = RawCullTheme.colors.reject),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(36.dp).testTag("batch_reject_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridPhotoCard(
    photo: PhotoAsset,
    isCurrent: Boolean,
    isChecked: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(photo.id) { mutableStateOf(value = true) }

    LaunchedEffect(photo.id) {
        isLoading = true
        val jpegUri = photo.companionFileUri?.toUri()
        bitmap = RawPreviewLoader.loadThumbnail(
            context = context,
            uri = photo.fileUri.toUri(),
            id = photo.previewCacheKey,
            format = photo.format,
            maxDimension = 360,
            companionUri = jpegUri,
        )
        isLoading = false
    }

    val borderColor = when {
        isChecked -> RawCullTheme.colors.pick
        isCurrent -> PrimaryCyan
        photo.cullStatus == CullStatus.PICK -> PickBorder
        photo.cullStatus == CullStatus.REJECT -> RejectBorder
        else -> HairlineWhite
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.TileCorner))
            .background(CardSurface)
            .border(if (isCurrent || isChecked) 2.dp else 1.dp, borderColor, RoundedCornerShape(Dimens.TileCorner))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = photo.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (isLoading) {
                CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(24.dp))
            }

            // Top-Left: RAW format badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ScrimStronger)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = photo.badgeText,
                    color = if (photo.hasCompanion) PairedMint else if (photo.format == PhotoFormat.ARW) FormatOrange else PrimaryCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (photo.xmpSyncStatus == XmpSyncStatus.ERROR) {
                SyncErrorDot(modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            }

            // Top-Right: Selection checkbox or Cull Badge
            if (isMultiSelectMode) {
                Icon(
                    imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (isChecked) RawCullTheme.colors.pick else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                )
            } else {
                when (photo.cullStatus) {
                    CullStatus.PICK -> Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(RawCullTheme.colors.pick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                    CullStatus.REJECT -> Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(RawCullTheme.colors.reject),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    CullStatus.UNFLAGGED -> Unit
                }
            }

            // Bottom inside image: Star Rating
            if (photo.rating > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ScrimStronger)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = RawCullTheme.colors.star, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = photo.rating.toString(), color = RawCullTheme.colors.star, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Color label dot
            if (photo.colorLabel != ColorLabel.NONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(RawCullTheme.colors.label(photo.colorLabel))
                        .border(1.dp, Color.White, CircleShape)
                )
            }
        }

        // Details below thumbnail
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = photo.fileName,
                color = if (isCurrent) PrimaryCyan else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = listOfNotNull(photo.shutterSpeed, photo.aperture, photo.iso?.let { "ISO $it" })
                    .joinToString(" • ")
                    .ifEmpty { " " },
                color = TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
