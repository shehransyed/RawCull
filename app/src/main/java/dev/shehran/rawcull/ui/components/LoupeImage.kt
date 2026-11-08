package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.preview.PreviewImage
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.data.preview.PreviewLane
import dev.shehran.rawcull.data.preview.PreviewResult
import dev.shehran.rawcull.data.preview.RegionSource
import dev.shehran.rawcull.preview.RegionMath
import dev.shehran.rawcull.preview.SourceRect
import kotlinx.coroutines.delay
import androidx.compose.runtime.staticCompositionLocalOf

/** D11: a tile is decoded this long after the last change to the zoom or pan. */
private const val TILE_SETTLE_MS = 120L
private const val PLACEHOLDER_THUMBNAIL_PX = 512

/** A decoded region of the 100 % source, in that source's own orientation. */
internal class LoupeTile(val bitmap: Bitmap, val rect: SourceRect, val sampleSize: Int)

/** The current photo's region decoder, opened on the first zoom past the base preview. */
private class RegionHolder {
    var region: RegionSource? = null

    fun close() {
        region?.close()
        region = null
    }
}

/** Debug builds: R5.9's timing overlay over the loupe. */
val LocalShowPreviewTiming = staticCompositionLocalOf { false }

/**
 * What the loupe is showing, for the pill and the timing overlay.
 *
 * @param fromMemory the image was already in RAM, so its timing is from when it was made.
 */
data class LoupeContent(val image: PreviewImage?, val unavailable: String?, val fromMemory: Boolean = false)

/** "Embedded JPEG 1616 × 1080 · FILE 131 ms (open 21 · scan 0 · read 1 · decode 100)". */
fun previewTimingText(content: LoupeContent): String? {
    val image = content.image ?: return null
    val source = image.info?.description ?: "${image.bitmap.width} × ${image.bitmap.height}"
    if (content.fromMemory) return "$source · RAM"
    val t = image.timing
    fun ms(value: Double) = "%.0f".format(value)
    return "$source · ${t.hit} ${ms(t.totalMs)} ms (open ${ms(t.openMs)} · scan ${ms(t.scanMs)} · read ${ms(t.readMs)} · decode ${ms(t.decodeMs)})"
}

/**
 * One photo in the loupe (D8, D10, D11), shared by the single loupe and compare. The fit preview
 * is asked for at the view's real pixel size, kept in source orientation and rotated as it's
 * drawn. Past the base preview's resolution, one region of the 100 % source (the visible part
 * plus a margin) is decoded once the gesture settles and drawn on top; the old tile stays until
 * the new one is ready, and the base preview is always underneath.
 *
 * Gestures belong to the caller, which drives [zoom].
 */
@Composable
fun LoupeImage(
    photo: PhotoAsset?,
    zoom: LoupeZoomState,
    modifier: Modifier = Modifier,
    onContent: (LoupeContent) -> Unit = {},
    placeholder: @Composable () -> Unit = {},
    peaking: PeakingStyle? = LocalPeaking.current,
) {
    val previews = LocalPreviewRepository.current
    val source = remember(photo?.fileUri, photo?.fileSizeBytes, photo?.fileLastModified, photo?.companionFileUri, photo?.companionFileSizeBytes) {
        photo?.let(PreviewKeys::source)
    }
    val key = source?.key
    var image by remember(key) { mutableStateOf<PreviewImage?>(null) }
    var unavailable by remember(key) { mutableStateOf<String?>(null) }
    var fromMemory by remember(key) { mutableStateOf(false) }
    var tile by remember(key) { mutableStateOf<LoupeTile?>(null) }
    val thumbnail = remember(key) { source?.let { previews?.peekThumbnail(it, PLACEHOLDER_THUMBNAIL_PX) } }
    val holder = remember(key) { RegionHolder() }
    DisposableEffect(holder) { onDispose { holder.close() } }

    val width = zoom.viewWidth
    val height = zoom.viewHeight
    LaunchedEffect(key, width, height) {
        if (source == null || previews == null || width <= 0 || height <= 0) return@LaunchedEffect
        val peeked = previews.peekFit(source, width, height)
        val ready = peeked
            ?: when (val result = previews.fitPreview(source, width, height, PreviewLane.LOUPE_CURRENT)) {
                is PreviewResult.Ready -> result.image
                is PreviewResult.Unavailable -> {
                    if (image == null) unavailable = result.reason
                    null
                }
            }
        if (ready != null) {
            fromMemory = peeked != null
            image = ready
            unavailable = null
        }
    }

    LaunchedEffect(image, unavailable, fromMemory) { onContent(LoupeContent(image, unavailable, fromMemory)) }

    // Each photo starts at fit (R4's behaviour).
    DisposableEffect(key) {
        zoom.reset()
        onDispose { }
    }
    // The zoom model works on the 100 % source, upright. Applied before this frame draws, so the
    // first frame of a new photo is already at its own fit.
    SideEffect {
        val shown = image ?: return@SideEffect
        val full = shown.full
        if (full != null) {
            zoom.setFull(
                RegionMath.displayWidth(full.info.width, full.info.height, full.orientation),
                RegionMath.displayHeight(full.info.width, full.info.height, full.orientation),
            )
        } else {
            val bitmap = shown.bitmap
            zoom.setFull(
                RegionMath.displayWidth(bitmap.width, bitmap.height, shown.orientation),
                RegionMath.displayHeight(bitmap.width, bitmap.height, shown.orientation),
            )
        }
    }

    val scale = zoom.scale
    val panX = zoom.panX
    val panY = zoom.panY
    val atFit = zoom.isAtFit
    val shownImage = image
    val shownFull = shownImage?.full
    val wantsTile = shownImage != null && shownFull != null && !atFit &&
        RegionMath.needsTile(
            scale,
            maxOf(shownImage.bitmap.width, shownImage.bitmap.height),
            maxOf(shownFull.info.width, shownFull.info.height),
        )
    var region by remember(key) { mutableStateOf<RegionSource?>(null) }
    // Opened once per photo, on the first zoom that needs it, and kept until the photo changes.
    LaunchedEffect(key, wantsTile) {
        if (!wantsTile || region != null || source == null || previews == null) return@LaunchedEffect
        region = previews.regionSource(source)?.also { holder.region = it }
    }
    LaunchedEffect(region, wantsTile, scale, panX, panY, width, height) {
        if (!wantsTile) {
            tile = null
            return@LaunchedEffect
        }
        val region = region ?: return@LaunchedEffect
        delay(TILE_SETTLE_MS)
        val plan = RegionMath.planTile(region.width, region.height, region.orientation, width, height, scale, panX, panY)
            ?: return@LaunchedEffect
        val current = tile
        if (current != null && current.sampleSize == plan.sampleSize && current.rect.contains(plan.source)) return@LaunchedEffect
        val bitmap = region.decode(plan.source, plan.sampleSize) ?: return@LaunchedEffect
        tile = LoupeTile(bitmap, plan.source, plan.sampleSize)
    }

    // R6 D7: peaking at display scale, over whatever is drawn (the fit preview or the 100 % tile).
    val view = zoom.view()
    val peakingRequest = if (peaking != null && shownImage != null && view != null) {
        PeakingRequest(shownImage, tile, view, peaking.threshold)
    } else {
        null
    }
    val peakingMask = rememberPeakingMask(peakingRequest)

    Box(
        // A zoomed image is larger than its view: in compare it would cover the other pane.
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { zoom.setView(it.width, it.height) }
            .pointerInput(zoom) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> zoom.pointer = event.changes.firstOrNull()?.position
                            PointerEventType.Exit -> zoom.pointer = null
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        if (shown != null) {
            val shownTile = tile
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { photo?.fileName?.let { contentDescription = it } }
                    .testTag("loupe_raw_image"),
            ) {
                drawLoupe(shown, shownTile, zoom)
            }
            if (peaking != null && peakingMask != null) PeakingMaskLayer(peakingMask, peaking.color)
        } else if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = photo?.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (unavailable == null) {
            placeholder()
        }
    }
}

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

/** Where the base preview and the tile land on screen, in view pixels. */
internal class LoupeGeometry(val baseMatrix: Matrix, val tileMatrix: Matrix?)

/** A snapshot of [LoupeZoomState] for drawing: the view, the upright 100 % source, scale and pan. */
internal data class LoupeView(
    val width: Int,
    val height: Int,
    val fullWidth: Int,
    val fullHeight: Int,
    val scale: Float,
    val panX: Float,
    val panY: Float,
    val atFit: Boolean,
)

internal fun LoupeZoomState.view(): LoupeView? =
    if (isKnown) LoupeView(viewWidth, viewHeight, fullWidth, fullHeight, scale, panX, panY, isAtFit) else null

/** The base preview stretched over the whole upright image, then the tile over its part of it. */
internal fun loupeGeometry(image: PreviewImage, tile: LoupeTile?, view: LoupeView): LoupeGeometry? {
    val scale = view.scale
    val fullW = view.fullWidth.toFloat()
    val fullH = view.fullHeight.toFloat()
    if (fullW <= 0f || fullH <= 0f) return null
    val originX = view.width / 2f + view.panX - fullW * scale / 2f
    val originY = view.height / 2f + view.panY - fullH * scale / 2f

    val base = image.bitmap
    val baseW = RegionMath.displayWidth(base.width, base.height, image.orientation).toFloat()
    val baseH = RegionMath.displayHeight(base.width, base.height, image.orientation).toFloat()
    val baseMatrix = orientationMatrix(base.width, base.height, image.orientation).apply {
        postScale(fullW / baseW * scale, fullH / baseH * scale)
        postTranslate(originX, originY)
    }
    val full = image.full
    // The tile's pixels → the 100 % source → upright → screen.
    val tileMatrix = if (full != null && tile != null) {
        Matrix().apply {
            setScale(tile.rect.width.toFloat() / tile.bitmap.width, tile.rect.height.toFloat() / tile.bitmap.height)
            postTranslate(tile.rect.left.toFloat(), tile.rect.top.toFloat())
            postConcat(orientationMatrix(full.info.width, full.info.height, full.orientation))
            postScale(scale, scale)
            postTranslate(originX, originY)
        }
    } else {
        null
    }
    return LoupeGeometry(baseMatrix, tileMatrix)
}

private fun DrawScope.drawLoupe(image: PreviewImage, tile: LoupeTile?, zoom: LoupeZoomState) {
    val view = LoupeView(
        size.width.toInt(), size.height.toInt(), zoom.fullWidth, zoom.fullHeight, zoom.scale, zoom.panX, zoom.panY, zoom.isAtFit,
    )
    val geometry = loupeGeometry(image, tile, view) ?: return
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.drawBitmap(image.bitmap, geometry.baseMatrix, bitmapPaint)
        if (tile != null && geometry.tileMatrix != null) native.drawBitmap(tile.bitmap, geometry.tileMatrix, bitmapPaint)
    }
}

private fun orientationMatrix(width: Int, height: Int, orientation: Int): Matrix {
    val t = RegionMath.transform(width, height, orientation)
    return Matrix().apply { setValues(floatArrayOf(t.a, t.b, t.c, t.d, t.e, t.f, 0f, 0f, 1f)) }
}

private fun SourceRect.contains(other: SourceRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
