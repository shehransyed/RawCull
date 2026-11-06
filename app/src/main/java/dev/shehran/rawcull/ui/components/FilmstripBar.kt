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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.RawPreviewLoader

@Composable
fun FilmstripBar(
    photos: List<PhotoAsset>,
    selectedPhotoId: String?,
    selectedPhotoIds: Set<String>,
    isMultiSelectMode: Boolean,
    onSelectIndex: (Int) -> Unit,
    onToggleSelectPhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Keyed on the position too, so the selected photo stays in view when a re-sort moves it.
    val selectedIndex = remember(photos, selectedPhotoId) { photos.indexOfFirst { it.id == selectedPhotoId } }
    LaunchedEffect(selectedPhotoId, selectedIndex) {
        if (selectedIndex >= 0) {
            listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(115.dp)
            .testTag("filmstrip_bar"),
        color = Color(0xFF0F141A),
        tonalElevation = 4.dp,
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(photos, key = { _, item -> item.id }) { index, photo ->
                val isCurrent = photo.id == selectedPhotoId
                val isChecked = selectedPhotoIds.contains(photo.id)

                FilmstripThumbnailItem(
                    photo = photo,
                    isCurrent = isCurrent,
                    isChecked = isChecked,
                    isMultiSelectMode = isMultiSelectMode,
                    onClick = {
                        if (isMultiSelectMode) {
                            onToggleSelectPhoto(photo.id)
                        } else {
                            onSelectIndex(index)
                        }
                    },
                    modifier = Modifier.testTag("filmstrip_item_$index"),
                )
            }
        }
    }
}

@Composable
private fun FilmstripThumbnailItem(
    photo: PhotoAsset,
    isCurrent: Boolean,
    isChecked: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var thumbBitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(photo.id) { mutableStateOf(value = true) }

    LaunchedEffect(photo.id) {
        isLoading = true
        val jpegUri = photo.companionFileUri?.toUri()
        thumbBitmap = RawPreviewLoader.loadThumbnail(
            context = context,
            uri = photo.fileUri.toUri(),
            id = photo.previewCacheKey,
            format = photo.format,
            maxDimension = 240,
            companionUri = jpegUri,
        )
        isLoading = false
    }

    val borderColor = when {
        isCurrent -> Color(0xFF38BDF8)
        isChecked -> Color(0xFF10B981)
        photo.cullStatus == CullStatus.PICK -> Color(0x6610B981)
        photo.cullStatus == CullStatus.REJECT -> Color(0x66EF4444)
        else -> Color(0x22FFFFFF)
    }

    val borderWidth = if (isCurrent || isChecked) 2.5.dp else 1.dp

    Column(
        modifier = modifier
            .width(110.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161E27))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap!!.asImageBitmap(),
                    contentDescription = photo.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF38BDF8)
                )
            }

            // Top-left: Format badge (RAW+JPG or ARW/DNG)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = photo.badgeText,
                    color = if (photo.hasCompanion) Color(0xFFA7F3D0) else if (photo.format == PhotoFormat.ARW) Color(0xFFF97316) else Color(0xFF38BDF8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (photo.xmpSyncStatus == XmpSyncStatus.ERROR) {
                SyncErrorDot(modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
            }

            // Top-right: Checkbox in multi-select mode or Cull badge
            if (isMultiSelectMode) {
                Icon(
                    imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = "Select",
                    tint = if (isChecked) Color(0xFF10B981) else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                )
            } else {
                when (photo.cullStatus) {
                    CullStatus.PICK -> Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                    }
                    CullStatus.REJECT -> Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                    CullStatus.UNFLAGGED -> Unit
                }
            }

            // Bottom row inside thumbnail: Star rating + Color label
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (photo.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(10.dp))
                        Text(
                            text = photo.rating.toString(),
                            color = Color(0xFFFBBF24),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (photo.colorLabel != ColorLabel.NONE) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(photo.colorLabel.hexColor))
                    )
                }
            }
        }

        // Bottom File Name Label
        Text(
            text = photo.fileName,
            color = if (isCurrent) Color(0xFF38BDF8) else Color(0xFFCBD5E1),
            fontSize = 10.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
