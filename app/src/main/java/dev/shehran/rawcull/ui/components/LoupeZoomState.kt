package dev.shehran.rawcull.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.shehran.rawcull.preview.RegionMath
import kotlin.math.roundToInt

private const val FIT_TOLERANCE = 1.001f

/**
 * The loupe's zoom (D10), in [RegionMath]'s model: a scale is screen pixels per pixel of the
 * 100 % source, so 1 is 100 %, and a pan is how far the image centre sits from the view's.
 * Until the view and the source sizes are known, the loupe is at fit.
 */
@Stable
class LoupeZoomState {
    var viewWidth by mutableIntStateOf(0)
        private set
    var viewHeight by mutableIntStateOf(0)
        private set

    /** The 100 % source, upright. */
    var fullWidth by mutableIntStateOf(0)
        private set
    var fullHeight by mutableIntStateOf(0)
        private set

    /** Zero while at fit, so a resize keeps fitting. */
    private var zoomed by mutableFloatStateOf(0f)
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set

    /** Where a mouse last hovered, so `Z` zooms there. */
    var pointer by mutableStateOf<Offset?>(null)

    val isKnown: Boolean get() = viewWidth > 0 && viewHeight > 0 && fullWidth > 0 && fullHeight > 0

    val fitScale: Float get() = if (isKnown) RegionMath.fitScale(fullWidth, fullHeight, viewWidth, viewHeight) else 1f

    val isAtFit: Boolean get() = !isKnown || zoomed <= fitScale * FIT_TOLERANCE

    val scale: Float get() = if (isAtFit) fitScale else zoomed

    /** The source is no larger than fit: there's nothing more to see at 100 %. */
    val isPreviewOnly: Boolean get() = isKnown && RegionMath.isPreviewOnly(fitScale)

    val percent: Int get() = (scale * 100).roundToInt()

    fun setView(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        reclamp()
    }

    fun setFull(width: Int, height: Int) {
        if (width == fullWidth && height == fullHeight) return
        fullWidth = width
        fullHeight = height
        reclamp()
    }

    fun reset() {
        zoomed = 0f
        panX = 0f
        panY = 0f
    }

    /** Fit ↔ 100 %, keeping the point under [focus] (view pixels) where it is. */
    fun toggle(focus: Offset? = pointer) {
        if (!isKnown) return
        if (!isAtFit) {
            reset()
            return
        }
        zoomAround(RegionMath.toggleScale(fitScale), focus, Offset.Zero)
    }

    /** A pinch or a drag: [zoom] is relative, [pan] in view pixels. */
    fun transform(pan: Offset, zoom: Float, centroid: Offset?) {
        if (!isKnown) return
        zoomAround(scale * zoom, centroid, pan)
    }

    private fun zoomAround(target: Float, focus: Offset?, pan: Offset) {
        val old = scale
        val new = target.coerceIn(fitScale, RegionMath.maxScale(fitScale))
        var x = panX
        var y = panY
        if (focus != null) {
            x = RegionMath.panForZoom(focus.x, viewWidth, x, old, new)
            y = RegionMath.panForZoom(focus.y, viewHeight, y, old, new)
        } else {
            x *= new / old
            y *= new / old
        }
        apply(new, x + pan.x, y + pan.y)
    }

    private fun apply(new: Float, x: Float, y: Float) {
        if (new <= fitScale * FIT_TOLERANCE) {
            reset()
            return
        }
        zoomed = new
        panX = RegionMath.clampPan(x, fullWidth, viewWidth, new)
        panY = RegionMath.clampPan(y, fullHeight, viewHeight, new)
    }

    /** After a resize or rotation: stay at fit, or stay zoomed within the new limits. */
    private fun reclamp() {
        if (!isKnown || zoomed == 0f) return
        apply(zoomed.coerceAtMost(RegionMath.maxScale(fitScale)), panX, panY)
    }
}

@Composable
fun rememberLoupeZoomState(): LoupeZoomState = remember { LoupeZoomState() }

/**
 * The zoom pill's text (D10): the real percentage, plus *Preview only W × H* when 100 % isn't the
 * camera's full resolution: the source is no larger than fit, or clearly smaller than the sensor
 * ([sensorLongEdge], 0 if unknown), as with Sony's 1616 px embedded previews.
 */
fun zoomPillText(zoom: LoupeZoomState, sensorLongEdge: Int = 0): String {
    val fullLongEdge = maxOf(zoom.fullWidth, zoom.fullHeight)
    val smallerThanSensor = zoom.isKnown && sensorLongEdge > 0 && fullLongEdge < sensorLongEdge * SENSOR_MATCH
    return if (zoom.isPreviewOnly || smallerThanSensor) {
        "${zoom.percent}% · Preview only ${zoom.fullWidth} × ${zoom.fullHeight}"
    } else {
        "${zoom.percent}%"
    }
}

/** Camera JPEGs can be a few pixels short of the sensor's size; that's still full resolution. */
private const val SENSOR_MATCH = 0.9f
