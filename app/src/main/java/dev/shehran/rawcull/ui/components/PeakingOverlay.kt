package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import dev.shehran.rawcull.data.preview.PreviewImage
import dev.shehran.rawcull.library.PeakingKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Focus peaking as the loupe draws it (R6 D7); null in [LocalPeaking] means off. */
@Immutable
data class PeakingStyle(val color: Color, val threshold: Float)

/** Set once by the Library, so the loupe, compare, full window and slideshow all follow it. */
val LocalPeaking = compositionLocalOf<PeakingStyle?> { null }

/** The CPU path's long edge limit (D8). */
internal const val PEAKING_MAX_EDGE = 2560

/** A zoom or pan has to settle this long before the CPU mask is redone. */
private const val PEAKING_SETTLE_MS = 80L

/** Everything a mask depends on. [image] and [tile] compare by identity. */
internal data class PeakingRequest(val image: PreviewImage, val tile: LoupeTile?, val view: LoupeView, val threshold: Float)

/**
 * A finished mask: one byte of alpha per pixel (ALPHA_8, so the paint colour tints it), to be
 * drawn into [dest] in view pixels. [marked] is the kernel's output, kept for tests.
 */
internal class PeakingMask<K>(val key: K, val bitmap: Bitmap, val dest: RectF, val marked: BooleanArray)

/**
 * Runs one job at a time and keeps the newest result: a new key cancels the job in flight, so a
 * new photo or zoom never waits for an old mask. [resultFor] gives nothing for a stale key, which
 * is how "the photo shows first, the mask follows" holds.
 */
internal class LatestJob<K, R : Any>(private val scope: CoroutineScope, private val work: suspend (K) -> R?) {
    var result by mutableStateOf<Pair<K, R>?>(null)
        private set
    private var requested: K? = null
    private var job: Job? = null

    fun request(key: K?) {
        if (key == requested) return
        requested = key
        job?.cancel()
        job = null
        if (key == null || result?.first == key) return
        job = scope.launch {
            val value = work(key) ?: return@launch
            if (requested == key) result = key to value
        }
    }

    fun resultFor(key: K?): R? = result?.takeIf { key != null && it.first == key }?.second
}

/** The CPU mask for [request]: kept per composition, recomputed off the main thread (D8). */
@Composable
internal fun rememberPeakingMask(request: PeakingRequest?): PeakingMask<PeakingRequest>? {
    val scope = rememberCoroutineScope()
    val runner = remember(scope) {
        val copies = SoftCopies()
        LatestJob<PeakingRequest, PeakingMask<PeakingRequest>>(scope) { computePeakingMask(it, copies) }
    }
    LaunchedEffect(request) { runner.request(request) }
    return runner.resultFor(request)
}

/** The mask over the photo, tinted with [color]. */
@Composable
internal fun PeakingMaskLayer(mask: PeakingMask<*>, color: Color) {
    val paint = remember { Paint() }
    Canvas(modifier = Modifier.fillMaxSize().testTag("peaking_overlay")) {
        paint.color = color.toArgb()
        drawIntoCanvas { it.nativeCanvas.drawBitmap(mask.bitmap, null, mask.dest, paint) }
    }
}

/** Software copies of the last base preview and tile, so a zoom doesn't read a hardware bitmap back again. */
private class SoftCopies {
    @Volatile private var base: Pair<Bitmap, Bitmap>? = null
    @Volatile private var tile: Pair<Bitmap, Bitmap>? = null

    fun base(bitmap: Bitmap): Bitmap = base?.takeIf { it.first === bitmap }?.second
        ?: softCopy(bitmap).also { base = bitmap to it }

    fun tile(bitmap: Bitmap): Bitmap = tile?.takeIf { it.first === bitmap }?.second
        ?: softCopy(bitmap).also { tile = bitmap to it }
}

private suspend fun computePeakingMask(request: PeakingRequest, copies: SoftCopies): PeakingMask<PeakingRequest>? =
    withContext(Dispatchers.Default) {
        if (!request.view.atFit) delay(PEAKING_SETTLE_MS)
        val geometry = loupeGeometry(request.image, request.tile, request.view) ?: return@withContext null
        val base = copies.base(request.image.bitmap)
        val tile = request.tile?.bitmap?.let(copies::tile)
        val context = coroutineContext
        renderPeakingMask(
            key = request,
            base = base,
            baseMatrix = geometry.baseMatrix,
            tile = tile,
            tileMatrix = geometry.tileMatrix,
            viewWidth = request.view.width,
            viewHeight = request.view.height,
            threshold = request.threshold,
            checkCancelled = { context.ensureActive() },
        )
    }

/**
 * D8's CPU path: draws what the loupe shows (the base preview, then the tile) into a software
 * bitmap covering the visible part of the photo, at most [PEAKING_MAX_EDGE] on the long edge,
 * and runs [PeakingKernel] on it. Only the photo's own rectangle is read, so its border against
 * the background is never an edge. Bitmaps must be software ones.
 */
internal fun <K> renderPeakingMask(
    key: K,
    base: Bitmap,
    baseMatrix: android.graphics.Matrix,
    tile: Bitmap?,
    tileMatrix: android.graphics.Matrix?,
    viewWidth: Int,
    viewHeight: Int,
    threshold: Float,
    checkCancelled: () -> Unit = {},
): PeakingMask<K>? {
    val rect = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())
    baseMatrix.mapRect(rect)
    if (!rect.intersect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())) return null
    val scale = min(1f, PEAKING_MAX_EDGE / max(rect.width(), rect.height()))
    val width = ceil(rect.width() * scale).toInt()
    val height = ceil(rect.height() * scale).toInt()
    if (width <= 0 || height <= 0) return null

    val drawn = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    NativeCanvas(drawn).apply {
        scale(scale, scale)
        translate(-rect.left, -rect.top)
        drawBitmap(base, baseMatrix, drawPaint)
        if (tile != null && tileMatrix != null) drawBitmap(tile, tileMatrix, drawPaint)
    }
    val pixels = IntArray(width * height)
    drawn.getPixels(pixels, 0, width, 0, 0, width, height)
    drawn.recycle()
    checkCancelled()

    val marked = PeakingKernel.mask(pixels, width, height, threshold, checkCancelled)
    val alpha = IntArray(width * height) { if (marked[it]) MARK else 0 }
    val mask = Bitmap.createBitmap(alpha, width, height, Bitmap.Config.ARGB_8888).extractAlpha()
    // Full-size coverage of the photo's rectangle on screen, whatever scale it was computed at.
    return PeakingMask(key, mask, RectF(rect.left, rect.top, rect.left + width / scale, rect.top + height / scale), marked)
}

private const val MARK = 0xFF000000.toInt()

private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

private fun softCopy(bitmap: Bitmap): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        bitmap
    }
