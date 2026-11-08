package dev.shehran.rawcull.data.export

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.library.ExportContent
import dev.shehran.rawcull.library.ExportFile
import dev.shehran.rawcull.library.ExportFileKind
import dev.shehran.rawcull.library.ExportItem
import dev.shehran.rawcull.library.ExportPlan
import dev.shehran.rawcull.library.ExportPlanner
import dev.shehran.rawcull.library.ExportTimes
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpProps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.TimeZone

/** Rewrites a JPEG copy's EXIF capture times in place; the offsets stay as they are (D10). */
fun interface JpegTimeWriter {
    @Throws(IOException::class)
    fun write(file: File, exifDateTime: String)
}

data class ExportOptions(val content: ExportContent, val keepOrder: Boolean)

data class ExportSummary(
    val exported: Int, // Photos with every file written
    val skipped: List<String>, // Photos with nothing to export (no JPEG, say)
    val failed: List<String>, // File names that couldn't be written
    val cancelled: Boolean,
)

/**
 * An ordered export (R7 D9-D11): copies into one folder in the given order, as `NNN_<name>`.
 * Never overwrites, checks each copy's size, and only ever changes the copies: library files and
 * sidecars are read, never written. Cancelling stops between photos; what's written stays.
 */
class ExportEngine(
    private val io: DocumentIo,
    private val jpegTimes: JpegTimeWriter,
    private val tempDir: File,
    private val toolkit: String,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** @param onProgress photos done, of how many, and the name being written. */
    suspend fun run(
        destinationTree: String,
        photos: List<PhotoAsset>,
        options: ExportOptions,
        onProgress: suspend (done: Int, total: Int, current: String?) -> Unit = { _, _, _ -> },
    ): ExportSummary {
        val caller = currentCoroutineContext().job
        // NonCancellable, so that a cancelled export still returns what it did: it stops between photos.
        // The switch to IO sits inside it, as a dispatched result would be dropped on the way back.
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) { export(caller, destinationTree, photos, options, onProgress) }
        }
    }

    private suspend fun export(
        caller: Job,
        destinationTree: String,
        photos: List<PhotoAsset>,
        options: ExportOptions,
        onProgress: suspend (done: Int, total: Int, current: String?) -> Unit,
    ): ExportSummary {
        val plan = ExportPlanner.plan(photos, options.content, options.keepOrder, now())
        val failed = ArrayList<String>()
        val folder = io.rootUri(destinationTree)
        val taken = try {
            io.listChildren(folder).mapTo(HashSet()) { key(it.name) }
        } catch (e: IOException) {
            return ExportSummary(0, plan.skipped, listOf("The folder can't be read: ${e.message}"), cancelled = false)
        }
        var exported = 0
        var cancelled = false
        for ((index, item) in plan.items.withIndex()) {
            if (!caller.isActive) {
                cancelled = true
                break
            }
            val names = freeNames(plan, item, taken)
            onProgress(index, plan.items.size, names.first())
            var whole = true
            item.files.zip(names).forEach { (file, name) ->
                val written = try {
                    write(folder, file, name, item)
                } catch (e: IOException) {
                    false
                }
                if (!written) {
                    whole = false
                    failed += name
                }
            }
            if (whole) exported++
        }
        if (!cancelled) onProgress(plan.items.size, plan.items.size, null)
        return ExportSummary(exported, plan.skipped, failed, cancelled)
    }

    /** The first attempt whose names are all free; they're taken at once, so later photos can't reuse them. */
    private fun freeNames(plan: ExportPlan, item: ExportItem, taken: MutableSet<String>): List<String> {
        var attempt = 0
        while (true) {
            val names = plan.names(item, attempt)
            if (names.none { key(it) in taken }) return names.also { list -> list.forEach { taken += key(it) } }
            attempt++
        }
    }

    /** False if the file was skipped for a reason that isn't a failure; throws when writing fails. */
    private fun write(folder: String, file: ExportFile, name: String, item: ExportItem): Boolean {
        val time = item.captureTime
        return when {
            file.kind == ExportFileKind.NEW_SIDECAR -> time == null || writeBytes(folder, name, sidecarWithTime(null, time))
            file.kind == ExportFileKind.SIDECAR -> {
                val original = try {
                    io.openInput(checkNotNull(file.sourceUri)).use(InputStream::readBytes)
                } catch (e: FileNotFoundException) {
                    // A sidecar the row knows of but the disk doesn't: a RAW still needs its time.
                    return time == null || writeBytes(folder, name, sidecarWithTime(null, time))
                }
                writeBytes(folder, name, if (time == null) original else sidecarWithTime(original, time))
            }
            file.kind == ExportFileKind.JPEG && time != null -> copyJpegWithTime(folder, checkNotNull(file.sourceUri), name, time)
            else -> copyBytes(folder, checkNotNull(file.sourceUri), name)
        }
    }

    /** The copy's own times; everything else in the sidecar stays. One that can't be parsed is copied as it is. */
    private fun sidecarWithTime(original: ByteArray?, time: Long): ByteArray {
        val document = if (original == null) XmpDocument.newEmpty(toolkit) else XmpDocument.parse(original).getOrNull() ?: return original
        listOf(XmpProps.EXIF_DATE_TIME_ORIGINAL, XmpProps.XMP_CREATE_DATE, XmpProps.PHOTOSHOP_DATE_CREATED).forEach { prop ->
            document.set(prop, ExportTimes.xmp(time, document.valueOf(prop), zone()))
        }
        return document.toBytes()
    }

    private fun copyJpegWithTime(folder: String, source: String, name: String, time: Long): Boolean {
        tempDir.mkdirs()
        val temp = File.createTempFile("export", ".jpg", tempDir)
        try {
            io.openInput(source).use { input -> temp.outputStream().use { input.copyTo(it) } }
            jpegTimes.write(temp, ExportTimes.exif(time, zone()))
            return create(folder, name) { uri ->
                io.openOutput(uri).use { out -> temp.inputStream().use { it.copyTo(out) } }
            }
        } finally {
            temp.delete()
        }
    }

    private fun copyBytes(folder: String, source: String, name: String): Boolean = create(folder, name) { uri ->
        io.openInput(source).use { input -> io.openOutput(uri).use { input.copyTo(it) } }
    }

    private fun writeBytes(folder: String, name: String, bytes: ByteArray): Boolean = create(folder, name) { uri ->
        io.openOutput(uri).use { it.write(bytes) }
        bytes.size.toLong()
    }

    /**
     * Creates [name] and fills it. octet-stream, so the provider adds no extension; a renamed or
     * short document is removed again, as it's ours.
     */
    private fun create(folder: String, name: String, fill: (String) -> Long): Boolean {
        val created = io.createFile(folder, MIME_BINARY, name)
        try {
            if (created.displayName != name) throw IOException("The folder renamed $name to ${created.displayName}")
            val written = fill(created.uri)
            val size = io.info(created.uri)?.sizeBytes
            if (size != written) throw IOException("$name is $size bytes, not $written")
            return true
        } catch (e: IOException) {
            io.delete(created.uri)
            throw e
        }
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT)

    private companion object {
        const val MIME_BINARY = "application/octet-stream"
    }
}
