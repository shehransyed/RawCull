package dev.shehran.rawcull.preview

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Pixels of the stored JPEG, before any rotation. */
data class SourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** The upright image, in source-sized pixels. */
data class DisplayRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** `x' = a·x + b·y + c`, `y' = d·x + e·y + f`: from source to upright. */
data class OrientationTransform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    fun x(x: Float, y: Float): Float = a * x + b * y + c
    fun y(x: Float, y: Float): Float = d * x + e * y + f
}

/** One region to decode: a rectangle of the source, at this `inSampleSize`. */
data class TilePlan(val source: SourceRect, val sampleSize: Int)

/**
 * The one coordinate model (D8) for what's on screen and what gets decoded. A scale is screen
 * pixels per source pixel, so 1 is 100 %. A pan is how far the image centre sits from the view's.
 */
object RegionMath {
    const val TILE_MARGIN = 0.25f
    private const val MAX_ZOOM = 2f

    fun swapsAxes(orientation: Int): Boolean = orientation in 5..8

    fun displayWidth(width: Int, height: Int, orientation: Int): Int = if (swapsAxes(orientation)) height else width

    fun displayHeight(width: Int, height: Int, orientation: Int): Int = if (swapsAxes(orientation)) width else height

    fun transform(width: Int, height: Int, orientation: Int): OrientationTransform {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (orientation) {
            2 -> OrientationTransform(-1f, 0f, w, 0f, 1f, 0f)
            3 -> OrientationTransform(-1f, 0f, w, 0f, -1f, h)
            4 -> OrientationTransform(1f, 0f, 0f, 0f, -1f, h)
            5 -> OrientationTransform(0f, 1f, 0f, 1f, 0f, 0f)
            6 -> OrientationTransform(0f, -1f, h, 1f, 0f, 0f)
            7 -> OrientationTransform(0f, -1f, h, -1f, 0f, w)
            8 -> OrientationTransform(0f, 1f, 0f, -1f, 0f, w)
            else -> OrientationTransform(1f, 0f, 0f, 0f, 1f, 0f)
        }
    }

    fun sourceToDisplay(rect: SourceRect, width: Int, height: Int, orientation: Int): DisplayRect {
        val t = transform(width, height, orientation)
        val l = rect.left.toFloat()
        val r = rect.right.toFloat()
        val top = rect.top.toFloat()
        val b = rect.bottom.toFloat()
        val x1 = t.x(l, top)
        val y1 = t.y(l, top)
        val x2 = t.x(r, b)
        val y2 = t.y(r, b)
        return DisplayRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /** Rounded outwards and clamped, so the result always covers the rectangle asked for. */
    fun displayToSource(rect: DisplayRect, width: Int, height: Int, orientation: Int): SourceRect {
        val dw = displayWidth(width, height, orientation).toFloat()
        val dh = displayHeight(width, height, orientation).toFloat()
        val (x1, y1) = inverse(rect.left, rect.top, width, height, orientation, dw, dh)
        val (x2, y2) = inverse(rect.right, rect.bottom, width, height, orientation, dw, dh)
        return SourceRect(
            left = floor(min(x1, x2)).toInt().coerceIn(0, width),
            top = floor(min(y1, y2)).toInt().coerceIn(0, height),
            right = ceil(max(x1, x2)).toInt().coerceIn(0, width),
            bottom = ceil(max(y1, y2)).toInt().coerceIn(0, height),
        )
    }

    private fun inverse(x: Float, y: Float, width: Int, height: Int, orientation: Int, dw: Float, dh: Float): Pair<Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (orientation) {
            2 -> (dw - x) to y
            3 -> (dw - x) to (dh - y)
            4 -> x to (dh - y)
            5 -> y to x
            6 -> y to (h - x)
            7 -> (w - y) to (h - x)
            8 -> (w - y) to x
            else -> x to y
        }
    }

    fun fitScale(displayWidth: Int, displayHeight: Int, viewWidth: Int, viewHeight: Int): Float =
        min(viewWidth.toFloat() / displayWidth, viewHeight.toFloat() / displayHeight)

    /** A source no larger than fit has nothing to show at 100 %, so the toggle goes to 2× fit (D10). */
    fun isPreviewOnly(fitScale: Float): Boolean = fitScale >= 1f

    fun toggleScale(fitScale: Float): Float = if (isPreviewOnly(fitScale)) fitScale * MAX_ZOOM else 1f

    fun maxScale(fitScale: Float): Float = max(MAX_ZOOM, fitScale * MAX_ZOOM)

    /** @return the pan on one axis, keeping the image over the view; centred if it's smaller than the view. */
    fun clampPan(pan: Float, displayExtent: Int, viewExtent: Int, scale: Float): Float {
        val slack = (displayExtent * scale - viewExtent) / 2f
        return if (slack <= 0f) 0f else pan.coerceIn(-slack, slack)
    }

    /** The pan that keeps the image point under [focus] (view pixels) in place across a zoom. */
    fun panForZoom(focus: Float, viewExtent: Int, pan: Float, oldScale: Float, newScale: Float): Float {
        val fromCentre = focus - viewExtent / 2f
        return fromCentre - (fromCentre - pan) * (newScale / oldScale)
    }

    /** What's on screen, cut to the image. */
    fun visibleRect(
        displayWidth: Int,
        displayHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        panX: Float,
        panY: Float,
    ): DisplayRect {
        val centreX = displayWidth / 2f - panX / scale
        val centreY = displayHeight / 2f - panY / scale
        val halfWidth = viewWidth / (2f * scale)
        val halfHeight = viewHeight / (2f * scale)
        return DisplayRect(
            left = (centreX - halfWidth).coerceIn(0f, displayWidth.toFloat()),
            top = (centreY - halfHeight).coerceIn(0f, displayHeight.toFloat()),
            right = (centreX + halfWidth).coerceIn(0f, displayWidth.toFloat()),
            bottom = (centreY + halfHeight).coerceIn(0f, displayHeight.toFloat()),
        )
    }

    fun withMargin(rect: DisplayRect, fraction: Float, displayWidth: Int, displayHeight: Int): DisplayRect {
        val x = rect.width * fraction
        val y = rect.height * fraction
        return DisplayRect(
            left = (rect.left - x).coerceAtLeast(0f),
            top = (rect.top - y).coerceAtLeast(0f),
            right = (rect.right + x).coerceAtMost(displayWidth.toFloat()),
            bottom = (rect.bottom + y).coerceAtMost(displayHeight.toFloat()),
        )
    }

    /** The largest power of two that still gives at least [scale] pixels per source pixel. */
    fun sampleSize(scale: Float): Int {
        if (scale >= 1f || scale <= 0f) return 1
        var sample = 1
        while (sample * 2 * scale <= 1f) sample *= 2
        return sample
    }

    /** Sample size for fitting a whole image of this size into a box, whichever way up it is drawn. */
    fun sampleSizeToFit(width: Int, height: Int, orientation: Int, boxWidth: Int, boxHeight: Int): Int {
        val dw = displayWidth(width, height, orientation)
        val dh = displayHeight(width, height, orientation)
        return sampleSize(fitScale(dw, dh, boxWidth, boxHeight))
    }

    /** True once the screen shows finer detail than the base preview holds. */
    fun needsTile(scale: Float, baseLongEdge: Int, sourceLongEdge: Int): Boolean =
        scale > baseLongEdge.toFloat() / sourceLongEdge

    /** D11's one tile: the visible part plus a margin, at the sample size the zoom calls for. */
    fun planTile(
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Int,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        panX: Float,
        panY: Float,
    ): TilePlan? {
        val dw = displayWidth(sourceWidth, sourceHeight, orientation)
        val dh = displayHeight(sourceWidth, sourceHeight, orientation)
        val visible = visibleRect(dw, dh, viewWidth, viewHeight, scale, panX, panY)
        if (visible.width <= 0f || visible.height <= 0f) return null
        val rect = displayToSource(withMargin(visible, TILE_MARGIN, dw, dh), sourceWidth, sourceHeight, orientation)
        if (rect.width <= 0 || rect.height <= 0) return null
        return TilePlan(rect, sampleSize(scale))
    }
}
