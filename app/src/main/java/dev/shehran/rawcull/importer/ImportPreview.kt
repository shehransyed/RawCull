package dev.shehran.rawcull.importer

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The toolbar of the preview step. */
data class PreviewOptions(
    val includeRaw: Boolean = true,
    val includeRaster: Boolean = true,
    val hideImported: Boolean = true,
)

/** One day of the preview grid. [key] is stable, so a collapsed day stays collapsed. */
data class PreviewDay(val key: String, val date: Long?, val candidates: List<ImportCandidate>)

enum class DayTick {
    ALL,
    SOME,
    NONE,
}

data class PreviewSummary(
    val selectedPhotos: Int = 0,
    val totalPhotos: Int = 0,
    val files: Int = 0,
    val bytes: Long = 0L,
    val rawCount: Int = 0,
    val rasterCount: Int = 0,
)

/**
 * What the preview step shows and what's ticked.
 *
 * Ticks are stored as overrides: the ids that differ from the default, which is "ticked unless
 * already imported". Candidates change while the scan fills them in, and a stored set of ticks
 * would have to chase them; this way a photo found to be imported unticks itself, and anything the
 * user touched stays as they left it.
 */
object ImportPreview {
    const val UNDATED_KEY = "undated"

    private fun ImportCandidate.images() = files.filter { !it.role.isSidecar }

    private fun CandidateFile.enabledIn(options: PreviewOptions): Boolean {
        val format = format ?: return false
        return if (format.isRaw) options.includeRaw else options.includeRaster
    }

    /** Imported means every image the current toggles would copy is already in the index. */
    fun isImported(candidate: ImportCandidate, options: PreviewOptions): Boolean {
        val enabled = candidate.images().filter { it.enabledIn(options) }
        return enabled.isNotEmpty() && enabled.all { it.alreadyImported }
    }

    fun isVisible(candidate: ImportCandidate, options: PreviewOptions): Boolean =
        candidate.images().any { it.enabledIn(options) } && !(options.hideImported && isImported(candidate, options))

    fun isSelected(candidate: ImportCandidate, options: PreviewOptions, overrides: Set<String>): Boolean =
        !isImported(candidate, options) != (candidate.id in overrides)

    fun visible(candidates: List<ImportCandidate>, options: PreviewOptions): List<ImportCandidate> =
        candidates.filter { isVisible(it, options) }

    /** Newest day first, photos in shooting order within a day. */
    fun days(
        candidates: List<ImportCandidate>,
        options: PreviewOptions,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<PreviewDay> {
        val calendar = Calendar.getInstance(timeZone, Locale.ROOT)
        return visible(candidates, options)
            .groupBy { candidate ->
                candidate.folderDate?.let { date ->
                    calendar.timeInMillis = date
                    "%04d-%02d-%02d".format(
                        Locale.ROOT,
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH) + 1,
                        calendar.get(Calendar.DAY_OF_MONTH),
                    )
                } ?: UNDATED_KEY
            }
            .map { (key, group) ->
                val inOrder = group.sortedWith(compareBy({ it.folderDate ?: Long.MAX_VALUE }, { it.stem }))
                PreviewDay(key, inOrder.firstNotNullOfOrNull { it.folderDate }, inOrder)
            }
            .sortedWith(compareBy<PreviewDay> { it.key == UNDATED_KEY }.thenByDescending { it.key })
    }

    fun dayTick(day: PreviewDay, options: PreviewOptions, overrides: Set<String>): DayTick {
        val selected = day.candidates.count { isSelected(it, options, overrides) }
        return when (selected) {
            0 -> DayTick.NONE
            day.candidates.size -> DayTick.ALL
            else -> DayTick.SOME
        }
    }

    /** New overrides with each of [targets] ticked or unticked. */
    fun setSelected(
        targets: List<ImportCandidate>,
        selected: Boolean,
        options: PreviewOptions,
        overrides: Set<String>,
    ): Set<String> {
        val next = overrides.toMutableSet()
        for (candidate in targets) {
            val isDefault = !isImported(candidate, options) == selected
            if (isDefault) next -= candidate.id else next += candidate.id
        }
        return next
    }

    /** The ids the import should take: ticked and visible, so a hidden photo is never copied by surprise. */
    fun selectedIds(candidates: List<ImportCandidate>, options: PreviewOptions, overrides: Set<String>): Set<String> =
        visible(candidates, options).filter { isSelected(it, options, overrides) }.map { it.id }.toSet()

    fun summary(candidates: List<ImportCandidate>, options: PreviewOptions, overrides: Set<String>): PreviewSummary {
        val visible = visible(candidates, options)
        val selected = visible.filter { isSelected(it, options, overrides) }
        val copied = selected.flatMap { candidate ->
            candidate.files.filter { it.role.isSidecar || it.enabledIn(options) }
        }
        return PreviewSummary(
            selectedPhotos = selected.size,
            totalPhotos = visible.size,
            files = copied.size,
            bytes = copied.sumOf { it.sizeBytes },
            rawCount = candidates.count { candidate -> candidate.images().any { it.format?.isRaw == true } },
            rasterCount = candidates.count { candidate -> candidate.images().any { it.format?.isRaw == false } },
        )
    }

    /** The tiles from [fromId] to [toId] in display order, for long-press and Shift+click ranges. */
    fun range(ordered: List<ImportCandidate>, fromId: String, toId: String): List<ImportCandidate> {
        val from = ordered.indexOfFirst { it.id == fromId }
        val to = ordered.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return emptyList()
        return ordered.subList(minOf(from, to), maxOf(from, to) + 1)
    }

    /** Unknown free space never blocks an import: a space check mustn't act on a guess. */
    fun isShortOfSpace(requiredBytes: Long, freeBytes: Long?): Boolean = freeBytes != null && requiredBytes > freeBytes
}
