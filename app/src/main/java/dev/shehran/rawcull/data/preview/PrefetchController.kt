package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.preview.PrefetchWindows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

const val PREFETCH_DEBOUNCE_MS = 100L

/**
 * Turns where the user is into prefetch requests (D9). Updates are debounced, so a fling or a
 * held arrow key asks once it settles; each request replaces the whole prefetch lane, which
 * cancels whatever moved out of range.
 */
@OptIn(FlowPreview::class)
class PrefetchController(
    private val repository: PreviewRepository,
    scope: CoroutineScope,
    debounceMs: Long = PREFETCH_DEBOUNCE_MS,
) {
    private sealed interface Window {
        data object Idle : Window

        class Requests(val build: () -> List<PrefetchRequest>) : Window
    }

    private val windows = MutableStateFlow<Window?>(null)
    private var lastLoupeIndex: Int? = null
    private var direction = 1

    init {
        scope.launch {
            windows.filterNotNull().debounce(debounceMs).collect { window ->
                when (window) {
                    Window.Idle -> repository.cancel(PreviewLane.PREFETCH)
                    is Window.Requests -> repository.prefetch(window.build(), PreviewLane.PREFETCH)
                }
            }
        }
    }

    /** The grid shows [firstVisible]..[lastVisible] of [count] photos in [columns] columns. */
    fun grid(firstVisible: Int, lastVisible: Int, columns: Int, count: Int, sizePx: Int, source: (Int) -> PreviewSource) {
        windows.value = Window.Requests {
            PrefetchWindows.grid(firstVisible, lastVisible, columns, count)
                .map { PrefetchRequest.Thumbnail(source(it), sizePx) }
        }
    }

    /** The loupe shows photo [current] of [count] at [widthPx] × [heightPx]. */
    fun loupe(current: Int, count: Int, compact: Boolean, widthPx: Int, heightPx: Int, source: (Int) -> PreviewSource) {
        lastLoupeIndex?.let { last -> if (current != last) direction = if (current > last) 1 else -1 }
        lastLoupeIndex = current
        val travel = direction
        windows.value = Window.Requests {
            PrefetchWindows.loupe(current, travel, count, compact)
                .map { PrefetchRequest.Fit(source(it), widthPx, heightPx) }
        }
    }

    /** Nothing to fetch ahead for any more, once things settle. */
    fun idle() {
        lastLoupeIndex = null
        windows.value = Window.Idle
    }
}
