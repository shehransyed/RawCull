package dev.shehran.rawcull.data.preview

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Records what was asked for and never has a picture. */
class FakePreviewRepository : PreviewRepository {
    val thumbnails = mutableListOf<Pair<PreviewSource, Int>>()
    val prefetches = mutableListOf<Pair<PreviewLane, List<PrefetchRequest>>>()
    val cancelled = mutableListOf<PreviewLane>()
    override val foregroundBusy = MutableStateFlow(false)

    override fun peekThumbnail(source: PreviewSource, sizePx: Int): Bitmap? = null

    override fun peekFit(source: PreviewSource, widthPx: Int, heightPx: Int): PreviewImage? = null

    override suspend fun thumbnail(source: PreviewSource, sizePx: Int, lane: PreviewLane): PreviewResult {
        thumbnails += source to sizePx
        return PreviewResult.Unavailable("fake")
    }

    override suspend fun fitPreview(source: PreviewSource, widthPx: Int, heightPx: Int, lane: PreviewLane): PreviewResult =
        PreviewResult.Unavailable("fake")

    override suspend fun regionSource(source: PreviewSource): RegionSource? = null

    override fun prefetch(requests: List<PrefetchRequest>, lane: PreviewLane) {
        prefetches += lane to requests
    }

    override fun cancel(lane: PreviewLane) {
        cancelled += lane
    }

    override suspend fun hasThumbnail(source: PreviewSource): Boolean = false

    override fun observeCacheBytes(): Flow<Long> = flowOf(0L)

    override suspend fun clearCache() = Unit

    override suspend fun forget(sources: List<PreviewSource>) = Unit
}
