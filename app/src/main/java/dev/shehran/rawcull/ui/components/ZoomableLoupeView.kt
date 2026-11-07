package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.ui.theme.FormatBadgeOrange
import dev.shehran.rawcull.ui.theme.FormatBadgePurple
import dev.shehran.rawcull.ui.theme.LoupeBackground
import dev.shehran.rawcull.ui.theme.PillSurface
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.PrimaryCyanVariant
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.ScrimMedium
import dev.shehran.rawcull.ui.theme.ScrimStrong
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.util.RawPreviewLoader

@Composable
fun ZoomableLoupeView(
    photo: PhotoAsset?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSyncChipClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var previewBitmap by remember(photo?.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(photo?.id) { mutableStateOf(value = true) }

    var scale by remember(photo?.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo?.id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(photo?.id) {
        if (photo == null) {
            previewBitmap = null
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        val uri = photo.fileUri.toUri()
        val jpegUri = photo.companionFileUri?.toUri()
        val bmp = RawPreviewLoader.loadPreview(
            context = context,
            uri = uri,
            id = photo.previewCacheKey,
            format = photo.format,
            targetWidth = 2560,
            targetHeight = 1600,
            companionUri = jpegUri,
        )
        previewBitmap = bmp
        isLoading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(LoupeBackground)
            .pointerInput(photo?.id) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // Center on tap
                            offset = Offset(
                                (size.width / 2f - tapOffset.x) * 1.5f,
                                (size.height / 2f - tapOffset.y) * 1.5f
                            )
                        }
                    }
                )
            }
            .pointerInput(photo?.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = newScale

                    offset = if (newScale > 1f) {
                        val maxPanX = (size.width * (newScale - 1f)) / 2f
                        val maxPanY = (size.height * (newScale - 1f)) / 2f
                        Offset(
                            x = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                            y = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                        )
                    } else {
                        Offset.Zero
                    }
                }
            }
            .testTag("zoomable_loupe_container"),
        contentAlignment = Alignment.Center
    ) {
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap!!.asImageBitmap(),
                contentDescription = photo?.fileName ?: "RAW Photo",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .testTag("loupe_raw_image"),
                contentScale = ContentScale.Fit
            )
        } else if (isLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = PrimaryCyan,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Decoding RAW sensor data...",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Text(
                text = "Select a RAW photo to inspect",
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Top-Left Overlays: File format, Star Rating, Cull Badge
        if (photo != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // RAW format badge
                val formatBg = when (photo.format) {
                    PhotoFormat.ARW -> FormatBadgeOrange
                    PhotoFormat.DNG -> PrimaryCyanVariant
                    else -> FormatBadgePurple
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(formatBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (photo.hasCompanion) photo.badgeText else photo.format.displayName.uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                SyncChip(status = photo.xmpSyncStatus, onClick = onSyncChipClick)

                // Cull Flag Badge
                when (photo.cullStatus) {
                    CullStatus.PICK -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(RawCullTheme.colors.pick)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PICK", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                    CullStatus.REJECT -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(RawCullTheme.colors.reject)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("REJECT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                    CullStatus.UNFLAGGED -> Unit
                }

                // Star Rating Badge
                if (photo.rating > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScrimStrong)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = RawCullTheme.colors.star,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${photo.rating}★", color = RawCullTheme.colors.star, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                // Color Label Dot
                if (photo.colorLabel != ColorLabel.NONE) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(RawCullTheme.colors.label(photo.colorLabel))
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }
            }
        }

        // Top-Right: Zoom control pill & 100% 1:1 Pixel loupe button
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Zoom indicator & 100% toggle
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .testTag("zoom_toggle_button"),
                color = PillSurface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Zoom Loupe",
                        tint = PrimaryCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 1:1 Pixel zoom button
            Surface(
                modifier = Modifier
                    .clip(CircleShape)
                    .testTag("one_to_one_zoom_button"),
                color = if (scale > 1.2f) PrimaryCyanVariant else PillSurface
            ) {
                IconButton(
                    onClick = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f // 1:1 inspection
                            offset = Offset.Zero
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (scale > 1.2f) Icons.Default.FitScreen else Icons.Default.ZoomIn,
                        contentDescription = "Toggle 100% Loupe",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Left Navigation Arrow
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(ScrimMedium)
                .testTag("prev_photo_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous RAW Photo",
                tint = Color.White
            )
        }

        // Right Navigation Arrow
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(ScrimMedium)
                .testTag("next_photo_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next RAW Photo",
                tint = Color.White
            )
        }
    }
}
