package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.data.preview.PreviewLane
import dev.shehran.rawcull.data.preview.PreviewRepository
import dev.shehran.rawcull.data.preview.PreviewResult
import dev.shehran.rawcull.util.HistogramCalculator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Loads the current photo's histogram from its software thumbnail. */
internal class HistogramLoader(
    private val store: LibraryStore,
    private val previews: PreviewRepository,
) {
    private var job: Job? = null
    private var key: String? = null

    /** A new Location: whatever is current must be loaded again. */
    fun reset() {
        key = null
    }

    /** Keyed on the photo's version, so enrichment batches don't restart it on every emission. */
    fun refresh() {
        val photo = store.value.currentPhoto ?: return
        if (photo.previewCacheKey == key) return
        key = photo.previewCacheKey
        job?.cancel()
        job = store.scope.launch {
            // Thumbnails are software bitmaps, so their pixels can be read.
            val result = previews.thumbnail(PreviewKeys.source(photo), HISTOGRAM_SIZE_PX, PreviewLane.VISIBLE)
            if (result is PreviewResult.Ready) {
                // Only decoration: pixels that can't be read mean no histogram, not a crash.
                val histogram = try {
                    HistogramCalculator.compute(result.image.bitmap)
                } catch (e: RuntimeException) {
                    store.log("No histogram for ${photo.fileName}: $e")
                    null
                }
                if (histogram != null) store.update { it.copy(currentHistogram = histogram) }
            }
        }
    }

    private companion object {
        const val HISTOGRAM_SIZE_PX = 320
    }
}
