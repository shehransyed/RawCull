package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.importer.ConflictNamer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What an ordered export copies (R7 D9). */
enum class ExportContent {
    ORIGINALS, // The RAW or JPEG, its companion and its sidecar
    JPEGS_ONLY, // The companion JPEG, or the file when it is a JPEG
    ;

    companion object {
        fun parse(stored: String?): ExportContent = entries.firstOrNull { it.name == stored } ?: ORIGINALS
    }
}

/** How a file is copied: byte for byte, or with its capture time rewritten in the copy (D10). */
enum class ExportFileKind { BYTES, JPEG, SIDECAR, NEW_SIDECAR }

/** One file to write. [sourceUri] is null only for a [ExportFileKind.NEW_SIDECAR]. */
data class ExportFile(val sourceUri: String?, val originalName: String, val kind: ExportFileKind)

/**
 * One photo: its files share [number], so they sort together, and [captureTime] is what its copies
 * get when the order is kept (null: they keep their own).
 */
data class ExportItem(
    val number: Int,
    val photoId: String,
    val stem: String,
    val files: List<ExportFile>,
    val captureTime: Long?,
)

data class ExportPlan(val items: List<ExportItem>, val digits: Int, val skipped: List<String>) {
    fun prefix(item: ExportItem): String = item.number.toString().padStart(digits, '0') + "_"

    /**
     * The names [item]'s files get. Attempt 0 is `NNN_<name>`; later attempts add `-n` after the
     * stem of every file in the group alike, so the group still shares one name.
     */
    fun names(item: ExportItem, attempt: Int): List<String> = item.files.map { file ->
        val stem = if (file.originalName.startsWith(item.stem, ignoreCase = true)) item.stem else file.originalName.substringBeforeLast('.')
        ConflictNamer.suffixed(prefix(item) + file.originalName, prefix(item) + stem, attempt)
    }
}

object ExportPlanner {
    /** One photo's step in the kept order (D10). */
    const val STEP_MILLIS = 10_000L

    /**
     * @param photos in the order they are exported.
     * @param now for telling a believable capture time, and the start when the first photo has none.
     */
    fun plan(photos: List<PhotoAsset>, content: ExportContent, keepOrder: Boolean, now: Long): ExportPlan {
        val skipped = ArrayList<String>()
        val kept = photos.mapNotNull { photo ->
            val files = when (content) {
                ExportContent.ORIGINALS -> originals(photo, keepOrder)
                ExportContent.JPEGS_ONLY -> listOfNotNull(jpegOf(photo))
            }
            if (files.isEmpty()) skipped += photo.fileName
            files.takeIf { it.isNotEmpty() }?.let { photo to it }
        }
        val start = kept.firstOrNull()?.first?.let { PlausibleDate.pick(it.dateTaken, it.fileLastModified, now) } ?: now
        val items = kept.mapIndexed { index, (photo, files) ->
            ExportItem(
                number = index + 1,
                photoId = photo.id,
                stem = photo.baseStem,
                files = files,
                captureTime = if (keepOrder) start + STEP_MILLIS * index else null,
            )
        }
        return ExportPlan(items, digitsFor(items.size), skipped)
    }

    /** NNN is zero-padded to at least three digits, and enough for the count. */
    fun digitsFor(count: Int): Int = maxOf(MIN_DIGITS, count.toString().length)

    private fun originals(photo: PhotoAsset, keepOrder: Boolean): List<ExportFile> = buildList {
        add(ExportFile(photo.fileUri, photo.fileName, kindOf(photo.format)))
        val companion = photo.companionFileUri?.takeIf { it.isNotBlank() }
        val companionName = photo.companionFileName
        if (companion != null && companionName != null) add(ExportFile(companion, companionName, kindOf(photo.companionFormat)))
        val sidecar = photo.xmpFileUri?.takeIf { it.isNotBlank() }
        when {
            sidecar != null -> add(ExportFile(sidecar, photo.sidecarFileName, ExportFileKind.SIDECAR))
            // A RAW has nowhere else to carry the new time; a JPEG carries it in its EXIF.
            keepOrder && photo.format.isRaw -> add(ExportFile(null, photo.sidecarFileName, ExportFileKind.NEW_SIDECAR))
        }
    }

    private fun jpegOf(photo: PhotoAsset): ExportFile? {
        val companion = photo.companionFileUri?.takeIf { it.isNotBlank() }
        val companionName = photo.companionFileName
        return when {
            companion != null && companionName != null && photo.companionFormat == PhotoFormat.JPEG ->
                ExportFile(companion, companionName, ExportFileKind.JPEG)
            photo.format == PhotoFormat.JPEG -> ExportFile(photo.fileUri, photo.fileName, ExportFileKind.JPEG)
            else -> null
        }
    }

    private fun kindOf(format: PhotoFormat?) = if (format == PhotoFormat.JPEG) ExportFileKind.JPEG else ExportFileKind.BYTES

    private const val MIN_DIGITS = 3
}

/** The time formats the copies get, in the zone capture times were read in (the device's). */
object ExportTimes {
    private val OFFSET = Regex("(Z|[+-]\\d{2}:?\\d{2})$")

    /** EXIF `DateTimeOriginal` style. */
    fun exif(time: Long, zone: TimeZone = TimeZone.getDefault()): String = format("yyyy:MM:dd HH:mm:ss", time, zone)

    /** An XMP date, keeping the zone offset [previous] had, if any. */
    fun xmp(time: Long, previous: String?, zone: TimeZone = TimeZone.getDefault()): String =
        format("yyyy-MM-dd'T'HH:mm:ss", time, zone) +
            (previous?.trim()?.takeIf { 'T' in it }?.let { OFFSET.find(it)?.value } ?: "")

    private fun format(pattern: String, time: Long, zone: TimeZone): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(time))
}
