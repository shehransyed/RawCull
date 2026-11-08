package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Reorder mode for the grid (R7 D7): a long press picks up a photo, or the selection it's in, and the drop moves it. */
class GridReorder(val onDrop: (pressedId: String, beforeId: String?) -> Unit)

/**
 * One drag in reorder mode. In-house rather than a library: the grid moves nothing while the finger
 * does; the tiles mark the gap a drop would fill, and the order changes once, on the drop.
 */
@Stable
internal class ReorderDrag {
    var pressedId by mutableStateOf<String?>(null)
        private set
    var moving by mutableStateOf<Set<String>>(emptySet())
        private set
    var point by mutableStateOf<Offset?>(null)
        private set

    /** The photo the block would land in front of; [atEnd] when it would go after the last one. */
    var beforeId by mutableStateOf<String?>(null)
        private set
    var atEnd by mutableStateOf(false)
        private set

    fun start(id: String, block: Set<String>, at: Offset) {
        pressedId = id
        moving = block
        point = at
    }

    fun aim(at: Offset, grid: LazyGridState, ids: List<String>) {
        point = at
        val hit = grid.nearestTile(at, ids.toSet()) ?: return
        val index = ids.indexOf(hit.key)
        val after = at.x > hit.offset.x + hit.size.width / 2f
        beforeId = if (after) ids.getOrNull(index + 1) else hit.key as String
        atEnd = beforeId == null
    }

    fun clear() {
        pressedId = null
        moving = emptySet()
        point = null
        beforeId = null
        atEnd = false
    }
}

/** Grid coordinates, as the selection drag uses them. */
private fun LazyGridState.nearestTile(point: Offset, ids: Set<String>): LazyGridItemInfo? =
    layoutInfo.visibleItemsInfo.filter { it.key in ids }.minByOrNull { item ->
        val dx = maxOf(item.offset.x - point.x, 0f, point.x - (item.offset.x + item.size.width))
        val dy = maxOf(item.offset.y - point.y, 0f, point.y - (item.offset.y + item.size.height))
        dx * dx + dy * dy
    }

/**
 * The long-press drag of reorder mode, in place of the selection drag.
 * Its lambdas are read on each event, so the caller can hand in the latest state.
 * @param onPoint where the finger is (null once it lifts) and how fast to scroll under it.
 */
internal fun Modifier.reorderDrag(
    enabled: Boolean,
    onDrop: (pressedId: String, beforeId: String?) -> Unit,
    grid: LazyGridState,
    drag: ReorderDrag,
    ids: () -> List<String>,
    selectedIds: () -> Set<String>,
    edgeZonePx: Float,
    scrollPxPerFrame: Float,
    onPoint: (Offset?, Float) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    fun end(drop: Boolean) {
        val pressed = drag.pressedId
        if (drop && pressed != null && (drag.beforeId != null || drag.atEnd)) onDrop(pressed, drag.beforeId)
        drag.clear()
        onPoint(null, 0f)
    }
    detectDragGesturesAfterLongPress(
        onDragStart = { point ->
            val id = grid.nearestTile(point, ids().toSet())?.key as? String ?: return@detectDragGesturesAfterLongPress
            val selected = selectedIds()
            drag.start(id, if (id in selected) selected else setOf(id), point)
            onPoint(point, 0f)
        },
        onDrag = { change, _ ->
            if (drag.pressedId == null) return@detectDragGesturesAfterLongPress
            val point = change.position
            drag.aim(point, grid, ids())
            val speed = when {
                point.y < edgeZonePx -> -scrollPxPerFrame
                point.y > size.height - edgeZonePx -> scrollPxPerFrame
                else -> 0f
            }
            onPoint(point, speed)
        },
        onDragEnd = { end(drop = true) },
        onDragCancel = { end(drop = false) },
    )
}

private val MarkWidth: Dp = 4.dp
private const val MOVING_ALPHA = 0.35f

/** A tile while photos are being dragged: dimmed if it's one of them, and marked where they'd land. */
internal fun Modifier.reorderMarks(drag: ReorderDrag, id: String, isLast: Boolean, mark: Color, gap: Dp): Modifier {
    if (drag.pressedId == null) return this
    val before = drag.beforeId == id
    val after = isLast && drag.atEnd
    // The mark sits outside the dimmed layer, so it stays bright on a tile that's moving.
    return drawWithContent {
        drawContent()
        if (before || after) {
            val width = MarkWidth.toPx()
            val x = if (before) -(gap.toPx() + width) / 2f else size.width + (gap.toPx() - width) / 2f
            drawRect(mark, topLeft = Offset(x, 0f), size = Size(width, size.height))
        }
    }.alpha(if (id in drag.moving) MOVING_ALPHA else 1f)
}
