package dev.shehran.rawcull.data.preview

import android.graphics.Bitmap
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.preview.SourceRect
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * One photo as the preview pipeline sees it.
 *
 * @param key changes whenever a file behind the photo changes; see [PreviewKeys].
 */
data class PreviewSource(
    val primaryUri: String,
    val format: PhotoFormat,
    val companionUri: String?,
    val key: String,
    val companionFormat: PhotoFormat? = null,
)

/** Who's waiting, most urgent first (D9). */
enum class PreviewLane {
    VISIBLE,
    LOUPE_CURRENT,
    PREFETCH,
    WARMUP,
}

enum class PreviewKind(val label: String) {
    EMBEDDED_JPEG("Embedded JPEG"),
    COMPANION_JPEG("Companion JPEG"),
    ORIGINAL_JPEG("JPEG"),
    HEIF("HEIF"),
    DECODED_RAW("Decoded RAW (slow)"),
}

/** What a picture really came from, for the UI to be honest about (D10). */
data class PreviewSourceInfo(val kind: PreviewKind, val width: Int, val height: Int) {
    val description: String get() = if (width > 0 && height > 0) "${kind.label} $width × $height" else kind.label
}

/** The largest source there is for a photo, and which way up its pixels are. */
data class FullSource(val info: PreviewSourceInfo, val orientation: Int)

enum class HitLevel {
    RAM,
    DISK,
    FILE,
}

/** Where a request's time went. */
data class PreviewTiming(
    val hit: HitLevel,
    val openMs: Double = 0.0,
    val scanMs: Double = 0.0,
    val readMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val totalMs: Double = 0.0,
)

/**
 * @param orientation what's left to apply at draw time; thumbnails are stored upright, so 1.
 * @param info null for a thumbnail from the cache, which no longer knows its source.
 */
class PreviewImage(
    val bitmap: Bitmap,
    val orientation: Int,
    val info: PreviewSourceInfo?,
    val full: FullSource?,
    val timing: PreviewTiming,
)

sealed interface PreviewResult {
    class Ready(val image: PreviewImage) : PreviewResult

    /** @param reason for the screen, as it stands. */
    data class Unavailable(val reason: String) : PreviewResult
}

sealed interface PrefetchRequest {
    val source: PreviewSource

    data class Thumbnail(override val source: PreviewSource, val sizePx: Int) : PrefetchRequest
    data class Fit(override val source: PreviewSource, val widthPx: Int, val heightPx: Int) : PrefetchRequest
}

/** One photo's 100 % source, open for region decoding. Close it when the photo goes off screen. */
interface RegionSource : Closeable {
    val width: Int
    val height: Int
    val orientation: Int
    val info: PreviewSourceInfo

    /** @return the region in source orientation, or null if it wouldn't decode. */
    suspend fun decode(rect: SourceRect, sampleSize: Int): Bitmap?
}

/** All image loading goes through here. */
interface PreviewRepository {
    /** Whatever RAM already holds, for a first frame without a flash. */
    fun peekThumbnail(source: PreviewSource, sizePx: Int): Bitmap?

    fun peekFit(source: PreviewSource, widthPx: Int, heightPx: Int): PreviewImage?

    suspend fun thumbnail(source: PreviewSource, sizePx: Int, lane: PreviewLane = PreviewLane.VISIBLE): PreviewResult

    suspend fun fitPreview(
        source: PreviewSource,
        widthPx: Int,
        heightPx: Int,
        lane: PreviewLane = PreviewLane.LOUPE_CURRENT,
    ): PreviewResult

    suspend fun regionSource(source: PreviewSource): RegionSource?

    /** The largest source there is for a photo, for the inspector (D10). From the index when it has the scan. */
    suspend fun fullSource(source: PreviewSource): FullSource?

    /** Replaces what's waiting in [lane]: anything not in [requests] is cancelled. */
    fun prefetch(requests: List<PrefetchRequest>, lane: PreviewLane = PreviewLane.PREFETCH)

    fun cancel(lane: PreviewLane)

    /** True while someone on screen is waiting for a picture, for background work to yield to. */
    val foregroundBusy: Flow<Boolean>

    /** True if a disk thumbnail already exists, so warm-up can skip it. */
    suspend fun hasThumbnail(source: PreviewSource): Boolean

    fun observeCacheBytes(): Flow<Long>

    suspend fun clearCache()

    /** Drops what RAM holds and keeps the disk, for the benchmark's disk stage. */
    fun clearMemory()

    /** Forgets RAM and disk for these photos, for a cold benchmark run. */
    suspend fun forget(sources: List<PreviewSource>)
}
