package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.shehran.rawcull.data.preview.PrefetchController
import dev.shehran.rawcull.data.preview.PreviewLane
import dev.shehran.rawcull.data.preview.PreviewSource

private data class VisibleRange(val first: Int, val last: Int, val columns: Int)

/**
 * Fetches thumbnails for the rows around what [state] shows (D9).
 *
 * @param indexOfKey each photo tile's grid key to its index in [items]; headers aren't in it.
 * @param source how to load an item. A window is resolved against the [items] it was made for,
 *   never a newer list: a shorter one (Cull in Library, R8.1) would be indexed past its end.
 */
@Composable
fun <T> GridPrefetch(
    state: LazyGridState,
    indexOfKey: Map<Any, Int>,
    items: List<T>,
    sizePx: Int,
    source: (T) -> PreviewSource,
) {
    val previews = LocalPreviewRepository.current
    if (previews != null) {
        val scope = rememberCoroutineScope()
        val controller = remember(previews) { PrefetchController(previews, scope) }
        val currentSource by rememberUpdatedState(source)
        LaunchedEffect(controller, indexOfKey, items, sizePx) {
            snapshotFlow {
                val info = state.layoutInfo
                var first = Int.MAX_VALUE
                var last = -1
                for (item in info.visibleItemsInfo) {
                    val index = indexOfKey[item.key] ?: continue
                    if (index < first) first = index
                    if (index > last) last = index
                }
                VisibleRange(first, last, info.maxSpan)
            }.collect { range ->
                if (range.last >= 0) {
                    controller.grid(range.first, range.last, range.columns, items, sizePx) { currentSource(it) }
                }
            }
        }
        DisposableEffect(previews) { onDispose { previews.cancel(PreviewLane.PREFETCH) } }
    }
}

/**
 * Fetches fit previews for the photos ahead of [current] in the direction of travel, and the one
 * behind (D9). The size must be the loupe's own, so the loupe finds them already in RAM.
 */
@Composable
fun <T> LoupePrefetch(
    current: Int,
    items: List<T>,
    compact: Boolean,
    widthPx: Int,
    heightPx: Int,
    source: (T) -> PreviewSource,
) {
    val previews = LocalPreviewRepository.current
    if (previews != null) {
        val scope = rememberCoroutineScope()
        val controller = remember(previews) { PrefetchController(previews, scope) }
        val currentSource by rememberUpdatedState(source)
        LaunchedEffect(controller, current, items, compact, widthPx, heightPx) {
            if (current >= 0 && widthPx > 0 && heightPx > 0) {
                controller.loupe(current, items, compact, widthPx, heightPx) { currentSource(it) }
            }
        }
        DisposableEffect(previews) { onDispose { previews.cancel(PreviewLane.PREFETCH) } }
    }
}
