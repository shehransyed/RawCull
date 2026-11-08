package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.library.LoupeSwipe
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

@Composable
fun ZoomableLoupeView(
    photo: PhotoAsset?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSyncChipClick: () -> Unit,
    zoomRequest: Int = 0,
    autoAdvance: Boolean = false,
    swipeToFlag: Boolean = false,
    onSwipe: ((LoupeSwipe) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onShown: ((String) -> Unit)? = null,
    onUserZoom: (() -> Unit)? = null,
    refitRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val swipe = rememberLoupeSwipeState()
    val zoom = rememberLoupeZoomState()
    var content by remember(photo?.id) { mutableStateOf(LoupeContent(null, null)) }

    // Only a press made while this view is up counts, not one left over from before it opened.
    var handledZoomRequest by remember { mutableIntStateOf(zoomRequest) }
    LaunchedEffect(zoomRequest) {
        if (zoomRequest != handledZoomRequest) {
            handledZoomRequest = zoomRequest
            zoom.toggle()
        }
    }

    // A new photo counts as shown once its fit preview (or its "No preview" state) is up.
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(content, photo?.id) {
        val id = photo?.id
        if (id != null && (content.image != null || content.unavailable != null)) currentOnShown?.invoke(id)
    }
    // The slideshow resumes at fit, whatever a pinch left behind.
    var handledRefit by remember { mutableIntStateOf(refitRequest) }
    LaunchedEffect(refitRequest) {
        if (refitRequest != handledRefit) {
            handledRefit = refitRequest
            zoom.reset()
        }
    }

    var heightPx by remember { mutableIntStateOf(0) }
    // A phone's loupe can be ~200 dp tall: the histogram then can't sit in the top column without covering the toolbar.
    val shortLoupe = heightPx < with(LocalDensity.current) { SHORT_LOUPE_DP.dp.roundToPx() }
    Box(
        modifier = modifier
            .onSizeChanged { heightPx = it.height }
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(LoupeBackground)
            .pointerInput(photo?.id) {
                detectTapGestures(
                    onTap = onTap?.let { tap -> { _: Offset -> tap() } },
                    onDoubleTap = { tapOffset ->
                        onUserZoom?.invoke()
                        zoom.toggle(tapOffset)
                    },
                )
            }
            .loupeGestures(
                key = photo?.id,
                isZoomed = { !zoom.isAtFit },
                onTransform = { pan, zoomBy, centroid, _ ->
                    if (zoomBy != 1f) onUserZoom?.invoke()
                    zoom.transform(pan, zoomBy, centroid)
                },
                state = swipe,
                swipeToFlag = swipeToFlag,
                onSwipe = onSwipe?.let { handler ->
                    { committed ->
                        haptics.onSwipeCommitted(committed)
                        handler(committed)
                    }
                },
            )
            .testTag("zoomable_loupe_container"),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            LoupeImage(
                photo = photo,
                zoom = zoom,
                onContent = { content = it },
                placeholder = { CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(44.dp)) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = swipe.offset.x
                        translationY = swipe.offset.y
                    },
            )
            content.unavailable?.let { reason ->
                Text(text = reason, color = TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("loupe_unavailable"))
            }
        } else {
            Text(
                text = "Select a RAW photo to inspect",
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SwipeBadge(swipe, Modifier.align(Alignment.Center))

        if (LocalShowPreviewTiming.current) {
            previewTimingText(content)?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ScrimStrong)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("preview_timing_overlay"),
                )
            }
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
        val sensorLongEdge = photo?.let { maxOf(it.width, it.height) } ?: 0
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (autoAdvance) {
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).testTag("auto_advance_indicator"),
                    color = PillSurface,
                ) {
                    Text(
                        text = "AUTO",
                        color = PrimaryCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

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
                        text = "${zoom.percent}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("zoom_pill_text"),
                    )
                }
            }

            // 1:1 Pixel zoom button
            Surface(
                modifier = Modifier
                    .clip(CircleShape)
                    .testTag("one_to_one_zoom_button"),
                color = if (!zoom.isAtFit) PrimaryCyanVariant else PillSurface
            ) {
                IconButton(
                    onClick = { zoom.toggle(focus = null) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (!zoom.isAtFit) Icons.Default.FitScreen else Icons.Default.ZoomIn,
                        contentDescription = "Toggle 100% Loupe",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
            // Under the pill rather than in it, so it never covers the badges on a phone (D10)
            previewOnlyText(zoom, sensorLongEdge)?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(PillSurface)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("zoom_preview_only"),
                )
            }
            // Last in the column, so it sits under the pill and the label and never over the badges (D10).
            if (!shortLoupe) LocalHistogramOverlay.current?.let { HistogramOverlay(it) }
        }

        if (shortLoupe) {
            // Bottom-left is free here: the toolbar is at the right and the arrows are at mid-height.
            LocalHistogramOverlay.current?.let { HistogramOverlay(it, Modifier.align(Alignment.BottomStart).padding(8.dp)) }
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

/** Below this loupe height the histogram overlay moves to the bottom-left corner (R6 D10). */
private const val SHORT_LOUPE_DP = 280
