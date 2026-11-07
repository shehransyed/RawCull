package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntRect
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.library.FolderSection
import dev.shehran.rawcull.library.GridNavigation
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.CardSurface
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

/** Key of a folder header; photo tiles use the photo id. */
private const val HEADER_KEY_PREFIX = "header:"

/** Distance from the grid's top or bottom edge where a selection drag starts scrolling. */
private val AutoScrollZone = 64.dp
private const val AUTO_SCROLL_PX_PER_FRAME = 18f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridViewGallery(
    photos: List<PhotoAsset>,
    sections: List<FolderSection>,
    selectedPhotoId: String?,
    selectedIds: Set<String>,
    onPhotoClick: (Int) -> Unit,
    onDragSelectStart: (String) -> Unit,
    onDragSelectTo: (String) -> Unit,
    onColumnsChanged: (Int) -> Unit,
    onClearFilters: (() -> Unit)?, // Set when something in the scope is hidden, not when it's empty
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.maxSpan }.collect(onColumnsChanged)
    }
    val selecting = selectedIds.isNotEmpty()
    val selectedItem = remember(photos, sections, selectedPhotoId) {
        val index = photos.indexOfFirst { it.id == selectedPhotoId }
        if (index >= 0) GridNavigation.gridItemIndex(index, sections) else -1
    }
    // Saved, so rotating keeps the scroll position instead of jumping back to the selection.
    var followedPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedPhotoId, selectedItem) {
        if (selectedItem >= 0 && selectedPhotoId != followedPhotoId) {
            followedPhotoId = selectedPhotoId
            gridState.scrollToItem(selectedItem)
        }
    }

    // Where a selection drag's finger is, and how fast the grid should scroll under it.
    var dragPoint by remember { mutableStateOf<Offset?>(null) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var dragSelecting by remember { mutableStateOf(false) }
    // Lifting after a long press that didn't move still reaches the tile as a click, which would
    // toggle straight back off the photo the long press just selected.
    var ignoreNextClick by remember { mutableStateOf(false) }
    val currentDragTo by rememberUpdatedState(onDragSelectTo)
    LaunchedEffect(autoScroll != 0f) {
        while (autoScroll != 0f) {
            gridState.scrollBy(autoScroll)
            dragPoint?.let { point -> gridState.photoIdAt(point)?.let(currentDragTo) }
            withFrameNanos { }
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
                    text = if (onClearFilters != null) "No photos match" else "No photos to show",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (onClearFilters != null) {
                    TextButton(onClick = onClearFilters, modifier = Modifier.testTag("clear_filters_button")) {
                        Text("Clear filters", color = PrimaryCyan)
                    }
                }
            }
        } else {
            val zone = with(LocalDensity.current) { AutoScrollZone.toPx() }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = Dimens.TileMinSize),
                contentPadding = PaddingValues(Dimens.GridPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.TileGap),
                verticalArrangement = Arrangement.spacedBy(Dimens.TileGap),
                // The grid's own scrolling would otherwise claim the drag once it passes touch slop.
                userScrollEnabled = !dragSelecting,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            ignoreNextClick = false
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { point ->
                                gridState.photoIdAt(point)?.let(onDragSelectStart)
                                dragPoint = point
                                dragSelecting = true
                                ignoreNextClick = true
                            },
                            onDrag = { change, _ ->
                                val point = change.position
                                dragPoint = point
                                gridState.photoIdAt(point)?.let(currentDragTo)
                                autoScroll = when {
                                    point.y < zone -> -AUTO_SCROLL_PX_PER_FRAME
                                    point.y > size.height - zone -> AUTO_SCROLL_PX_PER_FRAME
                                    else -> 0f
                                }
                            },
                            onDragEnd = {
                                dragPoint = null
                                autoScroll = 0f
                                dragSelecting = false
                            },
                            onDragCancel = {
                                dragPoint = null
                                autoScroll = 0f
                                dragSelecting = false
                            },
                        )
                    }
            ) {
                val spans = sections.ifEmpty { listOf(FolderSection("", 0, photos.size)) }
                spans.forEach { section ->
                    if (sections.isNotEmpty()) {
                        stickyHeader(key = "$HEADER_KEY_PREFIX${section.folder}", contentType = "header") {
                            FolderHeader(section)
                        }
                    }
                    items(
                        count = section.count,
                        key = { photos[section.firstIndex + it].id },
                        contentType = { "tile" },
                    ) { offset ->
                        val index = section.firstIndex + offset
                        val photo = photos[index]
                        GridPhotoCard(
                            photo = photo,
                            isCurrent = photo.id == selectedPhotoId,
                            isChecked = selectedIds.contains(photo.id),
                            isSelecting = selecting,
                            onClick = {
                                if (ignoreNextClick) ignoreNextClick = false else onPhotoClick(index)
                            },
                            onLongClick = { onDragSelectStart(photo.id) },
                            modifier = Modifier.testTag("grid_card_$index")
                        )
                    }
                }
            }
        }
    }
}

/** The photo tile under [point], in the grid's own coordinates. */
private fun LazyGridState.photoIdAt(point: Offset): String? {
    val hit = point.round()
    return layoutInfo.visibleItemsInfo
        .firstOrNull { it.size.toIntRect().contains(hit - it.offset) && it.key.toString().startsWith(HEADER_KEY_PREFIX).not() }
        ?.key as? String
}

/** Opaque, because tiles scroll underneath it. */
@Composable
private fun FolderHeader(section: FolderSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GridBackground)
            .padding(vertical = 6.dp)
            .testTag("grid_folder_header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.folder.ifEmpty { "Top level" },
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = section.count.toString(), color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun GridPhotoCard(
    photo: PhotoAsset,
    isCurrent: Boolean,
    isChecked: Boolean,
    isSelecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Adaptive cells are narrower than twice their minimum, so this always covers the tile.
    val thumbnailPx = with(LocalDensity.current) { (Dimens.TileMinSize * 2).roundToPx() }

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
            // The grid detects the long press itself, so screen readers need it spelled out here.
            .semantics {
                onLongClick(label = "Select") {
                    onLongClick()
                    true
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            PhotoThumbnail(
                photo = photo,
                sizePx = thumbnailPx,
                contentDescription = photo.fileName,
                modifier = Modifier.fillMaxSize(),
                placeholder = { CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(24.dp)) },
            )

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
            if (isSelecting) {
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
