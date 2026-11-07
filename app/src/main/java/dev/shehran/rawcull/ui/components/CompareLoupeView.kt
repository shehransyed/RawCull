package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.library.LoupeSwipe
import dev.shehran.rawcull.ui.theme.BarSurface
import dev.shehran.rawcull.ui.theme.CompareBackground
import dev.shehran.rawcull.ui.theme.ControlSurface
import dev.shehran.rawcull.ui.theme.CyanOutline
import dev.shehran.rawcull.ui.theme.DarkroomBackground
import dev.shehran.rawcull.ui.theme.HairlineWhite
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.ScrimStrong
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.util.RawPreviewLoader

@Composable
fun CompareLoupeView(
    photoA: PhotoAsset?,
    photoB: PhotoAsset?,
    onPickA: () -> Unit,
    onPickB: () -> Unit,
    onRejectA: () -> Unit,
    onRejectB: () -> Unit,
    zoomRequest: Int,
    swipeToFlag: Boolean,
    onSwipe: (LoupeSwipe) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val swipe = rememberLoupeSwipeState()
    var bitmapA by remember(photoA?.id) { mutableStateOf<Bitmap?>(null) }
    var bitmapB by remember(photoB?.id) { mutableStateOf<Bitmap?>(null) }
    var loadingA by remember(photoA?.id) { mutableStateOf(value = true) }
    var loadingB by remember(photoB?.id) { mutableStateOf(value = true) }

    // Synchronized zoom & pan state
    var sharedScale by remember { mutableFloatStateOf(1f) }
    var sharedOffset by remember { mutableStateOf(Offset.Zero) }

    var handledZoomRequest by remember { mutableIntStateOf(zoomRequest) }
    LaunchedEffect(zoomRequest) {
        if (zoomRequest != handledZoomRequest) {
            handledZoomRequest = zoomRequest
            sharedScale = if (sharedScale > 1.2f) 1f else 2.5f
            sharedOffset = Offset.Zero
        }
    }

    LaunchedEffect(photoA?.id) {
        if (photoA != null) {
            loadingA = true
            val jpegUriA = photoA.companionFileUri?.toUri()
            bitmapA = RawPreviewLoader.loadPreview(context, photoA.fileUri.toUri(), photoA.previewCacheKey, photoA.format, 1920, 1200, companionUri = jpegUriA)
            loadingA = false
        }
    }

    LaunchedEffect(photoB?.id) {
        if (photoB != null) {
            loadingB = true
            val jpegUriB = photoB.companionFileUri?.toUri()
            bitmapB = RawPreviewLoader.loadPreview(context, photoB.fileUri.toUri(), photoB.previewCacheKey, photoB.format, 1920, 1200, companionUri = jpegUriB)
            loadingB = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkroomBackground)
            .testTag("compare_loupe_container")
    ) {
        // Top Synchronized Zoom Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BarSurface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SPLIT COMPARE MODE (SYNCHRONIZED ZOOM)",
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryCyan,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(sharedScale * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )

                IconButton(
                    onClick = {
                        if (sharedScale > 1.2f) {
                            sharedScale = 1f
                            sharedOffset = Offset.Zero
                        } else {
                            sharedScale = 2.5f
                        }
                    },
                    modifier = Modifier.size(32.dp).testTag("compare_zoom_toggle")
                ) {
                    Icon(
                        imageVector = if (sharedScale > 1.2f) Icons.Default.FitScreen else Icons.Default.ZoomIn,
                        contentDescription = "Toggle Zoom",
                        tint = PrimaryCyan
                    )
                }
            }
        }

        // Split view side-by-side
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .loupeGestures(
                    key = photoA?.id,
                    isZoomed = { sharedScale > 1f },
                    onTransform = { pan, zoom, size ->
                        val newScale = (sharedScale * zoom).coerceIn(1f, 5f)
                        sharedScale = newScale
                        sharedOffset = if (newScale > 1f) {
                            val maxPanX = (size.width * (newScale - 1f)) / 2f
                            val maxPanY = (size.height * (newScale - 1f)) / 2f
                            Offset(
                                (sharedOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                (sharedOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                            )
                        } else {
                            Offset.Zero
                        }
                    },
                    state = swipe,
                    swipeToFlag = swipeToFlag,
                    onSwipe = { committed ->
                        haptics.onSwipeCommitted(committed)
                        onSwipe(committed)
                    },
                )
        ) {
            // Pane A (Left / Top)
            ComparePane(
                title = "Candidate A (Selected)",
                photo = photoA,
                bitmap = bitmapA,
                isLoading = loadingA,
                scale = sharedScale,
                offset = sharedOffset + swipe.offset, // Swipes act on the selected photo, so only it moves
                swipe = swipe,
                onPick = onPickA,
                onReject = onRejectA,
                modifier = Modifier.weight(1f).border(1.dp, CyanOutline)
            )

            // Pane B (Right / Bottom)
            ComparePane(
                title = "Candidate B (Comparison)",
                photo = photoB,
                bitmap = bitmapB,
                isLoading = loadingB,
                scale = sharedScale,
                offset = sharedOffset,
                onPick = onPickB,
                onReject = onRejectB,
                modifier = Modifier.weight(1f).border(1.dp, HairlineWhite)
            )
        }
    }
}

@Composable
private fun ComparePane(
    title: String,
    photo: PhotoAsset?,
    bitmap: Bitmap?,
    isLoading: Boolean,
    scale: Float,
    offset: Offset,
    onPick: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    swipe: LoupeSwipeState? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CompareBackground),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo?.fileName ?: title,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        } else if (isLoading) {
            CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(36.dp))
        } else {
            Text("No photo", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }

        swipe?.let { SwipeBadge(it, Modifier.align(Alignment.Center)) }

        // Top Info Overlay
        if (photo != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = ScrimStrong,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(
                            text = photo.fileName,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${photo.shutterSpeed ?: ""}  ${photo.aperture ?: ""}  ISO ${photo.iso ?: ""}",
                            color = PrimaryCyan,
                            fontSize = 11.sp
                        )
                    }
                }

                // Cull badge
                when (photo.cullStatus) {
                    CullStatus.PICK -> Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(RawCullTheme.colors.pick).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("PICKED", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    CullStatus.REJECT -> Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(RawCullTheme.colors.reject).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("REJECTED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    CullStatus.UNFLAGGED -> Unit
                }
            }

            // Bottom Quick Pick / Reject Action
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPick,
                    colors = ButtonDefaults.buttonColors(containerColor = RawCullTheme.colors.pick),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pick This", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = onReject,
                    colors = ButtonDefaults.buttonColors(containerColor = ControlSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = RawCullTheme.colors.reject, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject", color = RawCullTheme.colors.reject, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
