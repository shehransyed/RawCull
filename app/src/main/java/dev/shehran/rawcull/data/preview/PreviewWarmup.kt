package dev.shehran.rawcull.data.preview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a warm-up run stands, for the Library's top bar and Settings. */
data class WarmupState(
    val label: String = "",
    val total: Int = 0,
    /** Built or already there. */
    val done: Int = 0,
    val built: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    /** Held back while an import copies, or while the screen waits for a picture. */
    val isWaiting: Boolean = false,
    val etaMillis: Long? = null,
) {
    val isActive: Boolean get() = isRunning && done < total
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/**
 * D12: builds disk thumbnails ahead of time, one at a time in the warm-up lane. Before each photo
 * it waits while paused, while an import is copying, and while anyone on screen is waiting for a
 * picture; photos already on disk are skipped, so a restarted run picks up where it stopped.
 * In-process on purpose: it's only worth doing while the app is open.
 */
class PreviewWarmup(
    private val repository: PreviewRepository,
    private val scope: CoroutineScope,
    private val importCopying: Flow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(WarmupState())
    val state: StateFlow<WarmupState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var job: Job? = null

    /** Replaces whatever is running with [sources]. */
    fun start(label: String, sources: List<PreviewSource>) {
        job?.cancel()
        paused.value = false
        val unique = sources.distinctBy { it.key }
        _state.value = WarmupState(label = label, total = unique.size, isRunning = unique.isNotEmpty())
        if (unique.isEmpty()) return
        job = scope.launch { run(unique) }
    }

    fun pause() {
        paused.value = true
        _state.update { it.copy(isPaused = true) }
    }

    fun resume() {
        paused.value = false
        _state.update { it.copy(isPaused = false) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        paused.value = false
        _state.value = WarmupState()
    }

    private suspend fun run(sources: List<PreviewSource>) {
        val clear = combine(paused, importCopying, repository.foregroundBusy) { isPaused, copying, busy ->
            !isPaused && !copying && !busy
        }
        var buildMillis = 0L
        for (source in sources) {
            if (!clear.first()) {
                _state.update { it.copy(isWaiting = !it.isPaused) }
                clear.first { it }
                _state.update { it.copy(isWaiting = false) }
            }
            if (repository.hasThumbnail(source)) {
                _state.update { it.copy(done = it.done + 1) }
                continue
            }
            val started = clock()
            repository.thumbnail(source, THUMBNAIL_EDGE_PX, PreviewLane.WARMUP)
            buildMillis += clock() - started
            _state.update { state ->
                val built = state.built + 1
                val done = state.done + 1
                state.copy(done = done, built = built, etaMillis = buildMillis / built * (state.total - done))
            }
        }
        val finished = _state.value
        log("warm-up ${finished.label}: ${finished.built} built, ${finished.done - finished.built} already cached, ${buildMillis} ms")
        _state.update { it.copy(isRunning = false, etaMillis = null) }
    }

    companion object {
        /** The size the disk store keeps; every smaller bucket is made from it. */
        const val THUMBNAIL_EDGE_PX = 512
        const val LATEST_IMPORT_LABEL = "Latest Import"
    }
}
