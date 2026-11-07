package dev.shehran.rawcull.data.preview

import android.graphics.Bitmap
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.preview.MAX_PREVIEW_BYTES
import dev.shehran.rawcull.preview.PooledCandidate
import dev.shehran.rawcull.preview.PreviewChooser
import dev.shehran.rawcull.preview.PreviewFile
import dev.shehran.rawcull.preview.PreviewRequest
import dev.shehran.rawcull.preview.PreviewScan
import dev.shehran.rawcull.preview.PreviewScanner
import dev.shehran.rawcull.preview.RegionMath
import dev.shehran.rawcull.preview.SourceRect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val THUMBNAIL_EDGE = 512
private const val MEDIUM_THUMBNAIL_EDGE = 256
private const val SMALL_THUMBNAIL_EDGE = 128
private const val NANOS_PER_MILLI = 1_000_000.0
private const val NO_PREVIEW = "No preview"
private const val UNREADABLE = "Can't read this file"

/**
 * The pipeline: RAM, then the disk store, then the file, reading only the JPEG the chooser picked.
 * Everything that isn't plain logic sits behind [PreviewFiles] and [PreviewCodec].
 */
class DefaultPreviewRepository(
    private val files: PreviewFiles,
    private val codec: PreviewCodec,
    private val store: ThumbnailStore,
    private val dao: PreviewCacheDao,
    private val memory: PreviewMemory,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val decoding: CoroutineDispatcher,
    maxRunning: Int = DEFAULT_MAX_RUNNING,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : PreviewRepository {
    private val scheduler = PreviewScheduler(scope, maxRunning)

    override val foregroundBusy: Flow<Boolean> get() = scheduler.foregroundBusy

    /** The files one request has open, each at most once, and where its time went. */
    private inner class Request(private val source: PreviewSource) : Closeable {
        private var primary: OpenPreviewFile? = null
        private var companion: OpenPreviewFile? = null
        private val started = System.nanoTime()
        var openNanos = 0L
        var scanNanos = 0L
        var readNanos = 0L
        var decodeNanos = 0L

        fun file(which: PreviewFile): OpenPreviewFile = when (which) {
            PreviewFile.PRIMARY -> primary ?: open(source.primaryUri).also { primary = it }
            PreviewFile.COMPANION -> companion ?: open(source.companionUri!!).also { companion = it }
        }

        private fun open(uri: String): OpenPreviewFile = timed({ openNanos += it }) { files.open(uri) }

        fun timing(hit: HitLevel) = PreviewTiming(
            hit = hit,
            openMs = openNanos / NANOS_PER_MILLI,
            scanMs = scanNanos / NANOS_PER_MILLI,
            readMs = readNanos / NANOS_PER_MILLI,
            decodeMs = decodeNanos / NANOS_PER_MILLI,
            totalMs = (System.nanoTime() - started) / NANOS_PER_MILLI,
        )

        override fun close() {
            primary?.close()
            companion?.close()
        }
    }

    private inline fun <T> timed(add: (Long) -> Unit, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            add(System.nanoTime() - started)
        }
    }

    override fun peekThumbnail(source: PreviewSource, sizePx: Int): Bitmap? = memory.bitmap(thumbnailKey(source, bucket(sizePx)))

    override fun peekFit(source: PreviewSource, widthPx: Int, heightPx: Int): PreviewImage? =
        memory.preview(fitKey(source, widthPx, heightPx))

    override suspend fun thumbnail(source: PreviewSource, sizePx: Int, lane: PreviewLane): PreviewResult {
        val edge = bucket(sizePx)
        val key = thumbnailKey(source, edge)
        memory.bitmap(key)?.let { return PreviewResult.Ready(cachedThumbnail(it, HitLevel.RAM)) }
        return scheduler.run(key, lane) { loadThumbnail(source, edge, key, lane) }
    }

    override suspend fun fitPreview(source: PreviewSource, widthPx: Int, heightPx: Int, lane: PreviewLane): PreviewResult {
        val key = fitKey(source, widthPx, heightPx)
        memory.preview(key)?.let { return PreviewResult.Ready(it) }
        return scheduler.run(key, lane) { loadFit(source, widthPx, heightPx, key, lane) }
    }

    private fun cachedThumbnail(bitmap: Bitmap, hit: HitLevel) = PreviewImage(bitmap, 1, null, null, PreviewTiming(hit))

    private suspend fun loadThumbnail(source: PreviewSource, edge: Int, key: String, lane: PreviewLane): PreviewResult {
        memory.bitmap(key)?.let { return PreviewResult.Ready(cachedThumbnail(it, HitLevel.RAM)) }

        val started = System.nanoTime()
        val stored = memory.jpeg(source.key) ?: withContext(io) { store.read(source.key)?.jpeg }?.also { memory.putJpeg(source.key, it) }
        if (stored != null) {
            val bitmap = withContext(decoding) { codec.decode(stored, THUMBNAIL_EDGE / edge, hardware = false) }
            if (bitmap != null) {
                memory.putBitmap(key, bitmap)
                val total = (System.nanoTime() - started) / NANOS_PER_MILLI
                val timing = PreviewTiming(HitLevel.DISK, decodeMs = total, totalMs = total)
                logTiming(source, lane, "thumbnail", timing)
                return PreviewResult.Ready(PreviewImage(bitmap, 1, null, null, timing))
            }
        }
        return guarded(source, lane, "thumbnail") { request -> buildThumbnail(source, edge, key, request) }
    }

    /** Turns I/O failures into a state for the screen. Nothing about them gets cached. */
    private suspend fun guarded(
        source: PreviewSource,
        lane: PreviewLane,
        what: String,
        block: suspend (Request) -> PreviewResult,
    ): PreviewResult = Request(source).use { request ->
        val result = try {
            block(request)
        } catch (e: IOException) {
            log("failed key=${source.key.take(8)} $what ${e.message}")
            PreviewResult.Unavailable(UNREADABLE)
        } catch (e: SecurityException) {
            log("failed key=${source.key.take(8)} $what ${e.message}")
            PreviewResult.Unavailable(UNREADABLE)
        }
        if (result is PreviewResult.Ready) logTiming(source, lane, what, result.image.timing)
        result
    }

    private suspend fun buildThumbnail(source: PreviewSource, edge: Int, key: String, request: Request): PreviewResult {
        val pool = pool(source, request)
        for (entry in PreviewChooser.ranked(pool, PreviewRequest.Thumbnail(THUMBNAIL_EDGE))) {
            val bytes = readCandidate(entry, request) ?: continue
            coroutineContext.ensureActive()
            val sample = RegionMath.sampleSize(THUMBNAIL_EDGE.toFloat() / entry.candidate.longEdge)
            val built = withContext(decoding) {
                timed({ request.decodeNanos += it }) {
                    codec.decode(bytes, sample, hardware = false)?.let { codec.upright(it, entry.orientation, THUMBNAIL_EDGE) }
                }
            } ?: continue
            return finishThumbnail(source, built, edge, key, info(source, entry), request)
        }

        val fallback = platformTarget(source) ?: return PreviewResult.Unavailable(NO_PREVIEW)
        val decoded = withContext(decoding) {
            timed({ request.decodeNanos += it }) { files.decodeWithPlatform(fallback.first, THUMBNAIL_EDGE) }
        } ?: return PreviewResult.Unavailable(NO_PREVIEW)
        val upright = withContext(decoding) { codec.upright(decoded.bitmap, 1, THUMBNAIL_EDGE) }
        val info = PreviewSourceInfo(fallback.second, decoded.originalWidth, decoded.originalHeight)
        return finishThumbnail(source, upright, edge, key, info, request)
    }

    private suspend fun finishThumbnail(
        source: PreviewSource,
        built: Bitmap,
        edge: Int,
        key: String,
        info: PreviewSourceInfo,
        request: Request,
    ): PreviewResult {
        val jpeg = withContext(decoding) { timed({ request.decodeNanos += it }) { codec.encode(built) } }
        store.write(source.key, jpeg, built.width, built.height)
        memory.putJpeg(source.key, jpeg)
        val bitmap = if (edge == THUMBNAIL_EDGE) {
            built
        } else {
            withContext(decoding) { codec.decode(jpeg, THUMBNAIL_EDGE / edge, hardware = false) } ?: built
        }
        memory.putBitmap(key, bitmap)
        return PreviewResult.Ready(PreviewImage(bitmap, 1, info, null, request.timing(HitLevel.FILE)))
    }

    private suspend fun loadFit(source: PreviewSource, width: Int, height: Int, key: String, lane: PreviewLane): PreviewResult {
        memory.preview(key)?.let { return PreviewResult.Ready(it) }
        return guarded(source, lane, "fit") { request ->
            val pool = pool(source, request)
            val full = PreviewChooser.pick(pool, PreviewRequest.FullResolution)?.let { FullSource(info(source, it), it.orientation) }
            for (entry in PreviewChooser.ranked(pool, PreviewRequest.Fit(width, height))) {
                val bytes = readCandidate(entry, request) ?: continue
                coroutineContext.ensureActive()
                val c = entry.candidate
                val sample = RegionMath.sampleSizeToFit(c.width, c.height, entry.orientation, width, height)
                val bitmap = withContext(decoding) {
                    timed({ request.decodeNanos += it }) {
                        codec.decode(bytes, sample, hardware = true) ?: codec.decode(bytes, sample, hardware = false)
                    }
                } ?: continue
                val image = PreviewImage(bitmap, entry.orientation, info(source, entry), full, request.timing(HitLevel.FILE))
                memory.putPreview(key, image)
                return@guarded PreviewResult.Ready(image)
            }

            val fallback = platformTarget(source) ?: return@guarded PreviewResult.Unavailable(NO_PREVIEW)
            val decoded = withContext(decoding) {
                timed({ request.decodeNanos += it }) { files.decodeWithPlatform(fallback.first, maxOf(width, height)) }
            } ?: return@guarded PreviewResult.Unavailable(NO_PREVIEW)
            val info = PreviewSourceInfo(fallback.second, decoded.originalWidth, decoded.originalHeight)
            // Only HEIF can be region-decoded; a decoded RAW has nothing beyond this bitmap.
            val fullInfo = if (fallback.second == PreviewKind.HEIF) {
                info
            } else {
                PreviewSourceInfo(fallback.second, decoded.bitmap.width, decoded.bitmap.height)
            }
            val image = PreviewImage(decoded.bitmap, 1, info, FullSource(fullInfo, 1), request.timing(HitLevel.FILE))
            memory.putPreview(key, image)
            PreviewResult.Ready(image)
        }
    }

    /** The scan from the index if there is one, otherwise from the files, stored for next time. */
    private suspend fun pool(source: PreviewSource, request: Request): List<PooledCandidate> {
        dao.scan(source.key)?.let { row -> ScanCodec.decode(row.candidatesJson)?.let { return it } }
        val pool = withContext(io) {
            val primary = scan(source.format, request, PreviewFile.PRIMARY)
            val companion = if (source.companionUri != null) {
                scan(source.companionFormat ?: PhotoFormat.JPEG, request, PreviewFile.COMPANION)
            } else {
                null
            }
            PreviewChooser.pool(primary, companion)
        }
        val orientation = pool.firstOrNull { it.file == PreviewFile.PRIMARY }?.orientation ?: 1
        dao.putScan(ScanRow(source.key, ScanCodec.encode(pool), orientation, clock()))
        return pool
    }

    private fun scan(format: PhotoFormat, request: Request, which: PreviewFile): PreviewScan {
        val file = request.file(which)
        return timed({ request.scanNanos += it }) {
            file.startScan()
            PreviewScanner.forFormat(format).scan(file.source)
        }
    }

    /** Null if the bytes aren't where the scan said: the next candidate may still be fine. */
    private suspend fun readCandidate(entry: PooledCandidate, request: Request): ByteArray? = withContext(io) {
        val length = entry.candidate.length
        if (length > MAX_PREVIEW_BYTES) return@withContext null
        val file = request.file(entry.file)
        val bytes = timed({ request.readNanos += it }) { file.source.read(entry.candidate.offset, length.toInt()) }
        bytes.takeIf { it.size.toLong() == length }
    }

    /** D2's last resort, and how HEIF is always read. */
    private fun platformTarget(source: PreviewSource): Pair<String, PreviewKind>? = when {
        source.format == PhotoFormat.HEIF -> source.primaryUri to PreviewKind.HEIF
        source.companionFormat == PhotoFormat.HEIF && source.companionUri != null -> source.companionUri to PreviewKind.HEIF
        source.format == PhotoFormat.DNG -> source.primaryUri to PreviewKind.DECODED_RAW
        else -> null
    }

    private fun info(source: PreviewSource, entry: PooledCandidate): PreviewSourceInfo {
        val kind = when {
            entry.file == PreviewFile.COMPANION -> PreviewKind.COMPANION_JPEG
            source.format == PhotoFormat.JPEG -> PreviewKind.ORIGINAL_JPEG
            else -> PreviewKind.EMBEDDED_JPEG
        }
        return PreviewSourceInfo(kind, entry.candidate.width, entry.candidate.height)
    }

    override suspend fun regionSource(source: PreviewSource): RegionSource? = withContext(io) {
        try {
            Request(source).use { request -> openRegion(source, request) }
        } catch (e: IOException) {
            log("region failed key=${source.key.take(8)} ${e.message}")
            null
        } catch (e: SecurityException) {
            log("region failed key=${source.key.take(8)} ${e.message}")
            null
        }
    }

    private suspend fun openRegion(source: PreviewSource, request: Request): RegionSource? {
        val entry = PreviewChooser.pick(pool(source, request), PreviewRequest.FullResolution)
        if (entry == null) {
            val target = platformTarget(source)?.takeIf { it.second == PreviewKind.HEIF } ?: return null
            val decoder = files.regionDecoder(target.first) ?: return null
            return null.also { decoder.close() } // Size unknown here; the loupe keeps its base preview
        }
        val c = entry.candidate
        val wholeFile = c.offset == 0L
        val decoder = if (wholeFile) {
            val uri = if (entry.file == PreviewFile.COMPANION) source.companionUri!! else source.primaryUri
            files.regionDecoder(uri) // Stays on the descriptor: a 20 MB JPEG doesn't need to be on the heap
        } else {
            readCandidate(entry, request)?.let(files::regionDecoder) // Kept while the photo is current
        } ?: return null
        return PlatformRegionSource(decoder, c.width, c.height, entry.orientation, info(source, entry), decoding)
    }

    private class PlatformRegionSource(
        private val decoder: RegionDecoder,
        override val width: Int,
        override val height: Int,
        override val orientation: Int,
        override val info: PreviewSourceInfo,
        private val decoding: CoroutineDispatcher,
    ) : RegionSource {
        override suspend fun decode(rect: SourceRect, sampleSize: Int): Bitmap? =
            withContext(decoding) { decoder.decode(rect, sampleSize) }

        override fun close() = decoder.close()
    }

    override fun prefetch(requests: List<PrefetchRequest>, lane: PreviewLane) {
        val wanted = requests.map { it to requestKey(it) }
        scheduler.cancel(lane, keep = wanted.mapTo(HashSet()) { it.second })
        wanted.forEach { (request, key) ->
            when (request) {
                is PrefetchRequest.Thumbnail -> {
                    if (memory.bitmap(key) != null) return@forEach
                    scheduler.submit(key, lane) { loadThumbnail(request.source, bucket(request.sizePx), key, lane) }
                }
                is PrefetchRequest.Fit -> {
                    if (memory.preview(key) != null) return@forEach
                    scheduler.submit(key, lane) { loadFit(request.source, request.widthPx, request.heightPx, key, lane) }
                }
            }
        }
    }

    private fun requestKey(request: PrefetchRequest): String = when (request) {
        is PrefetchRequest.Thumbnail -> thumbnailKey(request.source, bucket(request.sizePx))
        is PrefetchRequest.Fit -> fitKey(request.source, request.widthPx, request.heightPx)
    }

    override fun cancel(lane: PreviewLane) = scheduler.cancel(lane)

    override suspend fun hasThumbnail(source: PreviewSource): Boolean = store.contains(source.key)

    override fun observeCacheBytes(): Flow<Long> = store.observeUsedBytes()

    override suspend fun clearCache() {
        memory.clear()
        store.clear()
    }

    override suspend fun forget(sources: List<PreviewSource>) {
        val keys = sources.mapTo(HashSet()) { it.key }
        memory.remove { key -> key.substringBefore(KEY_SEPARATOR) in keys }
        keys.chunked(SQL_BATCH).forEach {
            dao.deleteThumbnails(it)
            dao.deleteScans(it)
        }
    }

    private fun logTiming(source: PreviewSource, lane: PreviewLane, what: String, t: PreviewTiming) {
        log(
            "key=${source.key.take(8)} $what lane=$lane hit=${t.hit} " +
                "open=${ms(t.openMs)} scan=${ms(t.scanMs)} read=${ms(t.readMs)} decode=${ms(t.decodeMs)} total=${ms(t.totalMs)}",
        )
    }

    private fun ms(value: Double) = "%.1f".format(value)

    companion object {
        private const val DEFAULT_MAX_RUNNING = 4
        private const val KEY_SEPARATOR = '/'
        private const val SQL_BATCH = 500

        fun bucket(sizePx: Int): Int = when {
            sizePx <= SMALL_THUMBNAIL_EDGE -> SMALL_THUMBNAIL_EDGE
            sizePx <= MEDIUM_THUMBNAIL_EDGE -> MEDIUM_THUMBNAIL_EDGE
            else -> THUMBNAIL_EDGE
        }

        private fun thumbnailKey(source: PreviewSource, edge: Int) = "${source.key}${KEY_SEPARATOR}t$edge"

        private fun fitKey(source: PreviewSource, width: Int, height: Int) = "${source.key}${KEY_SEPARATOR}f${width}x$height"
    }
}
