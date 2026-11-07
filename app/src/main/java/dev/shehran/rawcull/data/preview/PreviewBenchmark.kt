package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.preview.RegionMath
import kotlin.math.ceil

/** One stage of the D13 benchmark, in the order it runs. */
enum class BenchmarkStage(val label: String, val targetMs: Double?) {
    COLD_THUMBNAIL("Cold thumbnail (file)", 80.0),
    DISK_THUMBNAIL("Cached thumbnail (disk)", 16.0),
    COLD_FIT("Cold loupe fit", 250.0),
    RAM_FIT("Next loupe photo (prefetched)", 50.0),
    TILE("100 % tile", 300.0),
}

/** What one request cost. Stages without a breakdown leave it at zero. */
data class BenchmarkSample(val stage: BenchmarkStage, val format: String, val timing: PreviewTiming)

/** D13's numbers, as a table to paste back into a planning chat. Pure, so it's tested on its own. */
object BenchmarkTable {
    /** Nearest rank: the smallest value with at least [percent] % of the samples at or below it. */
    fun percentile(values: List<Double>, percent: Int): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun format(samples: List<BenchmarkSample>, title: String): String {
        val lines = mutableListOf(
            title,
            "| Stage | Format | n | p50 | p90 | target | open p50 | scan p50 | read p50 | decode p50 |",
            "|---|---|---|---|---|---|---|---|---|---|",
        )
        val groups = samples.groupBy { it.stage to it.format }
        for (stage in BenchmarkStage.entries) {
            val formats = groups.keys.filter { it.first == stage }.map { it.second }.sorted()
            for (format in formats) {
                val group = groups.getValue(stage to format).map { it.timing }
                val total = group.map { it.totalMs }
                val target = stage.targetMs?.let { "≤ ${ms(it)}" } ?: "–"
                lines += "| ${stage.label} | $format | ${group.size} | ${ms(percentile(total, 50))} | ${ms(percentile(total, 90))} | " +
                    "$target | ${ms(percentile(group.map { it.openMs }, 50))} | ${ms(percentile(group.map { it.scanMs }, 50))} | " +
                    "${ms(percentile(group.map { it.readMs }, 50))} | ${ms(percentile(group.map { it.decodeMs }, 50))} |"
            }
        }
        return lines.joinToString("\n")
    }

    private fun ms(value: Double) = "%.1f".format(value)
}

/**
 * R5.9's *Benchmark previews in this folder*: cold and warm runs over [photos], stage by stage.
 * The cold run starts from nothing for these photos (RAM and disk forgotten), so it measures the
 * file path; the disk stage drops RAM only; the loupe stages follow the way culling uses them.
 */
class PreviewBenchmark(
    private val repository: PreviewRepository,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    /** A photo and the label its format goes under in the table. */
    data class Photo(val source: PreviewSource, val format: String)

    suspend fun run(
        photos: List<Photo>,
        fitWidth: Int,
        fitHeight: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<BenchmarkSample> {
        val samples = mutableListOf<BenchmarkSample>()
        val total = photos.size * BenchmarkStage.entries.size
        var done = 0
        fun step() = onProgress(++done, total)

        repository.forget(photos.map { it.source })
        for (photo in photos) {
            repository.thumbnail(photo.source, THUMBNAIL_PX, PreviewLane.VISIBLE).timing()
                ?.let { samples += BenchmarkSample(BenchmarkStage.COLD_THUMBNAIL, photo.format, it) }
            step()
        }
        repository.clearMemory()
        for (photo in photos) {
            repository.thumbnail(photo.source, THUMBNAIL_PX, PreviewLane.VISIBLE).timing()
                ?.let { samples += BenchmarkSample(BenchmarkStage.DISK_THUMBNAIL, photo.format, it) }
            step()
        }
        repository.clearMemory()
        // RAM holds only the prefetch window, so each photo is shown straight after it was fetched,
        // as a prefetched next photo is. A RAM hit hands back the image as it was made, timing and
        // all, so that stage is timed here.
        for (photo in photos) {
            repository.fitPreview(photo.source, fitWidth, fitHeight).timing()
                ?.let { samples += BenchmarkSample(BenchmarkStage.COLD_FIT, photo.format, it) }
            step()
            val started = clockNanos()
            val result = repository.fitPreview(photo.source, fitWidth, fitHeight)
            val elapsed = (clockNanos() - started) / NANOS_PER_MS
            if (result is PreviewResult.Ready) {
                samples += BenchmarkSample(BenchmarkStage.RAM_FIT, photo.format, PreviewTiming(HitLevel.RAM, totalMs = elapsed))
            }
            step()
        }
        for (photo in photos) {
            tile(photo, fitWidth, fitHeight)?.let { samples += BenchmarkSample(BenchmarkStage.TILE, photo.format, it) }
            step()
        }
        return samples
    }

    /** Opening the 100 % source and decoding the centre at 100 %, as a first zoom does. */
    private suspend fun tile(photo: Photo, viewWidth: Int, viewHeight: Int): PreviewTiming? {
        val started = clockNanos()
        val region = repository.regionSource(photo.source) ?: return null
        region.use {
            val opened = clockNanos()
            val plan = RegionMath.planTile(it.width, it.height, it.orientation, viewWidth, viewHeight, 1f, 0f, 0f) ?: return null
            it.decode(plan.source, plan.sampleSize) ?: return null
            val finished = clockNanos()
            return PreviewTiming(
                hit = HitLevel.FILE,
                openMs = (opened - started) / NANOS_PER_MS,
                decodeMs = (finished - opened) / NANOS_PER_MS,
                totalMs = (finished - started) / NANOS_PER_MS,
            )
        }
    }

    private fun PreviewResult.timing(): PreviewTiming? = (this as? PreviewResult.Ready)?.image?.timing

    private companion object {
        const val THUMBNAIL_PX = 512
        const val NANOS_PER_MS = 1_000_000.0
    }
}
