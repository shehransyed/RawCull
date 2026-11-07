package dev.shehran.rawcull.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.library.LoupeSwipe
import dev.shehran.rawcull.library.LoupeSwipes
import dev.shehran.rawcull.ui.theme.RawCullTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val FLING_DP_PER_SECOND = 1000

/** Where the photo is while a finger drags it. */
class LoupeSwipeState(private val scope: CoroutineScope) {
    private val animatable = Animatable(Offset.Zero, Offset.VectorConverter)
    val offset: Offset get() = animatable.value
    var height by mutableIntStateOf(0)

    fun drag(to: Offset) {
        scope.launch { animatable.snapTo(to) }
    }

    fun springBack() {
        scope.launch { animatable.animateTo(Offset.Zero, spring()) }
    }

    /** After a commit: the next photo starts in place rather than sliding back from the old one. */
    fun reset() {
        scope.launch { animatable.snapTo(Offset.Zero) }
    }
}

@Composable
fun rememberLoupeSwipeState(): LoupeSwipeState {
    val scope = rememberCoroutineScope()
    return remember(scope) { LoupeSwipeState(scope) }
}

/**
 * Pinch and pan while zoomed, D6 swipes at fit zoom. One gesture handler decides between them, so
 * a zoomed photo keeps its pan and a swipe can't also move the zoom.
 *
 * @param key restarts the handler, usually with the photo.
 * @param onSwipe null turns swipes off altogether.
 */
fun Modifier.loupeGestures(
    key: Any?,
    isZoomed: () -> Boolean,
    onTransform: (pan: Offset, zoom: Float, centroid: Offset, size: IntSize) -> Unit,
    state: LoupeSwipeState,
    swipeToFlag: Boolean,
    onSwipe: ((LoupeSwipe) -> Unit)?,
): Modifier = onSizeChanged { state.height = it.height }.pointerInput(key, swipeToFlag, onSwipe != null) {
    val flingVelocity = FLING_DP_PER_SECOND.dp.toPx()
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var transforming = false
        var vertical: Boolean? = null // Null until the swipe has picked its axis
        var travel = Offset.Zero

        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2 || isZoomed()) {
                if (vertical != null) state.springBack()
                transforming = true
                vertical = null
            }
            if (transforming) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onTransform(pan, zoom, event.calculateCentroid(useCurrent = true), size)
                    event.changes.forEach { it.consume() }
                }
            } else if (onSwipe != null) {
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                tracker.addPointerInputChange(change)
                travel += change.positionChange()
                if (vertical == null && travel.getDistance() > touchSlop) vertical = abs(travel.y) > abs(travel.x)
                val axis = vertical
                if (axis != null && (!axis || swipeToFlag)) {
                    state.drag(if (axis) Offset(0f, travel.y) else Offset(travel.x, 0f))
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })

        val axis = vertical
        if (axis != null && !transforming && onSwipe != null) {
            val velocity = tracker.calculateVelocity()
            val swipe = LoupeSwipes.resolve(
                vertical = axis,
                distance = if (axis) travel.y else travel.x,
                velocity = if (axis) velocity.y else velocity.x,
                extent = (if (axis) size.height else size.width).toFloat(),
                flingVelocity = flingVelocity,
                swipeToFlag = swipeToFlag,
            )
            if (swipe != null) {
                state.reset()
                onSwipe(swipe)
            } else {
                state.springBack()
            }
        }
    }
}

/** Fades in as the photo nears the point where letting go would commit. */
@Composable
fun SwipeBadge(state: LoupeSwipeState, modifier: Modifier = Modifier) {
    val y = state.offset.y
    if (y == 0f || state.height == 0) return
    val picking = y < 0
    val progress = (abs(y) / (state.height * LoupeSwipes.COMMIT_FRACTION)).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .alpha(progress)
            .clip(RoundedCornerShape(8.dp))
            .background(if (picking) RawCullTheme.colors.pick else RawCullTheme.colors.reject)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("swipe_badge"),
    ) {
        Text(
            text = if (picking) "PICK" else "REJECT",
            color = if (picking) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

/** Only ever called on a commit: a buzz on every twitch of the finger would mean nothing. */
fun HapticFeedback.onSwipeCommitted(swipe: LoupeSwipe) {
    performHapticFeedback(
        when (swipe) {
            LoupeSwipe.PICK -> HapticFeedbackType.Confirm
            LoupeSwipe.REJECT -> HapticFeedbackType.Reject
            LoupeSwipe.NEXT, LoupeSwipe.PREVIOUS -> HapticFeedbackType.SegmentTick
        },
    )
}
