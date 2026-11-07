package dev.shehran.rawcull.data.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs preview work by lane, at most [maxRunning] at a time, with at most one warm-up among them
 * so it can't take the slots a scroll is about to need. Requests for the same key share one task.
 * A task nobody wants any more is cancelled, queued or running.
 */
class PreviewScheduler(
    private val scope: CoroutineScope,
    private val maxRunning: Int,
) {
    private class Task<T>(val key: String, var lane: PreviewLane, val order: Long, val block: suspend () -> T) {
        val result = CompletableDeferred<T>()
        var waiters = 0
        var detached = false // Asked for by prefetch, so it runs with nobody waiting
        var job: Job? = null
        val started: Boolean get() = job != null
    }

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task<*>>()
    private var nextOrder = 0L
    private val busy = MutableStateFlow(false)

    /** True while a task in a foreground lane is queued or running. */
    val foregroundBusy: StateFlow<Boolean> = busy.asStateFlow()

    suspend fun <T> run(key: String, lane: PreviewLane, block: suspend () -> T): T {
        val task = synchronized(lock) {
            enlist(key, lane, block).also {
                it.waiters++
                pump()
            }
        }
        try {
            return task.result.await()
        } finally {
            synchronized(lock) {
                task.waiters--
                if (task.waiters == 0 && !task.detached) drop(task)
            }
        }
    }

    /** Fire and forget: runs until it finishes or [cancel] says otherwise. */
    fun <T> submit(key: String, lane: PreviewLane, block: suspend () -> T) {
        synchronized(lock) {
            enlist(key, lane, block).detached = true
            pump()
        }
    }

    /** Cancels what was submitted to [lane], except [keep]. Anyone still waiting keeps their task. */
    fun cancel(lane: PreviewLane, keep: Set<String> = emptySet()) {
        synchronized(lock) {
            tasks.values.filter { it.lane == lane && it.detached && it.key !in keep }.forEach {
                it.detached = false
                if (it.waiters == 0) drop(it)
            }
            pump()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> enlist(key: String, lane: PreviewLane, block: suspend () -> T): Task<T> {
        val existing = tasks[key] as Task<T>?
        if (existing != null) {
            if (lane < existing.lane) existing.lane = lane
            updateBusy()
            return existing
        }
        return Task(key, lane, nextOrder++, block).also {
            tasks[key] = it
            updateBusy()
        }
    }

    private fun drop(task: Task<*>) {
        if (tasks[task.key] !== task) return
        tasks.remove(task.key)
        task.job?.cancel()
        task.result.cancel()
        updateBusy()
        pump()
    }

    private fun pump() {
        while (true) {
            val running = tasks.values.count { it.started }
            if (running >= maxRunning) return
            val waiting = tasks.values.filter { !it.started }
            val next = waiting.minWithOrNull(compareBy({ it.lane }, { it.order })) ?: return
            if (next.lane == PreviewLane.WARMUP && tasks.values.any { it.started && it.lane == PreviewLane.WARMUP }) return
            start(next)
        }
    }

    private fun <T> start(task: Task<T>) {
        task.job = scope.launch {
            try {
                task.result.complete(task.block())
            } catch (e: CancellationException) {
                task.result.cancel(e)
                throw e
            } catch (e: Exception) {
                task.result.completeExceptionally(e)
            } finally {
                synchronized(lock) {
                    if (tasks[task.key] === task) tasks.remove(task.key)
                    updateBusy()
                    pump()
                }
            }
        }
    }

    private fun updateBusy() {
        busy.value = tasks.values.any { it.lane == PreviewLane.VISIBLE || it.lane == PreviewLane.LOUPE_CURRENT }
    }
}
