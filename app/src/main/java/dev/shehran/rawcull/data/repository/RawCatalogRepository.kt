package dev.shehran.rawcull.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.Converters
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.ExportConfig
import dev.shehran.rawcull.data.model.ExportProgress
import dev.shehran.rawcull.data.model.IngestFileSelection
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.util.SamplePhotosGenerator
import dev.shehran.rawcull.util.XmpSidecarGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.core.net.toUri

class RawCatalogRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val dao: RawPhotoDao,
    private val writeQueue: XmpWriteQueue,
) {
    private val converters = Converters()

    fun observeByRoot(rootUri: String): Flow<List<PhotoAsset>> = dao.observeByRoot(rootUri)

    /** Generates the demo shoot once, so flags set on it survive reopening it. */
    suspend fun loadSampleShoot() = withContext(Dispatchers.IO) {
        if (dao.getIdsByRoot(DEMO_ROOT_URI).isEmpty()) {
            dao.upsertAll(SamplePhotosGenerator.createSampleShoot(context))
        }
    }

    /** `.HIF` isn't an extension providers map to image/heif, so they would append one. */
    private fun exportMimeType(format: PhotoFormat): String =
        if (format == PhotoFormat.HEIF) "application/octet-stream" else format.mimeType

    /** Room commits first and the queue writes later, so nothing here waits for the card. */
    private suspend fun updateAll(ids: List<String>, update: suspend (List<String>) -> Unit) {
        database.withTransaction { ids.chunked(MAX_IDS_PER_QUERY).forEach { update(it) } }
        writeQueue.enqueue(ids)
    }

    suspend fun setCullStatus(id: String, status: CullStatus) = batchSetCullStatus(listOf(id), status)

    suspend fun setRating(id: String, rating: Int) = batchSetRating(listOf(id), rating)

    suspend fun setColorLabel(id: String, label: ColorLabel) = batchSetColorLabel(listOf(id), label)

    suspend fun batchSetCullStatus(ids: List<String>, status: CullStatus) = withContext(Dispatchers.IO) {
        updateAll(ids) { dao.updateCullStatus(it, status) }
    }

    suspend fun batchSetRating(ids: List<String>, rating: Int) = withContext(Dispatchers.IO) {
        updateAll(ids) { dao.updateRating(it, rating.coerceIn(0, 5)) }
    }

    suspend fun batchSetColorLabel(ids: List<String>, label: ColorLabel) = withContext(Dispatchers.IO) {
        updateAll(ids) { dao.updateColorLabel(it, label) }
    }

    suspend fun batchAddTag(ids: List<String>, tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        editTags(ids) { tags -> if (trimmed in tags) tags else tags + trimmed }
    }

    suspend fun removeTag(id: String, tag: String) = editTags(listOf(id)) { it - tag }

    private suspend fun editTags(ids: List<String>, edit: (List<String>) -> List<String>) = withContext(Dispatchers.IO) {
        val changed = database.withTransaction {
            ids.filter { id ->
                val tags = dao.getPhotoById(id)?.tags ?: return@filter false
                val edited = edit(tags)
                if (edited != tags) dao.updateTags(id, converters.fromTagsList(edited))
                edited != tags
            }
        }
        writeQueue.enqueue(changed)
    }

    /**
     * RapidReady-inspired Structured Ingest & Export Pipeline:
     * - Date-based folder structuring: YYYY/MM-DD/[Shoot_Name]
     * - RAW only, JPEG only, or Both RAW+JPEG handling
     * - Universal Adobe XMP sidecar write
     * - Live MB/s telemetry
     */
    fun exportCulledFiles(
        photosToExport: List<PhotoAsset>,
        config: ExportConfig
    ): Flow<ExportProgress> = flow {
        if (photosToExport.isEmpty()) {
            emit(ExportProgress(completed = true, totalFiles = 0, errorMessage = "No files selected to export"))
            return@flow
        }

        val destUri = config.destinationFolderUri.toUri()
        val destDoc = DocumentFile.fromTreeUri(context, destUri)
        if (destDoc == null || !destDoc.canWrite()) {
            emit(ExportProgress(completed = true, errorMessage = "Destination folder is not writable or inaccessible."))
            return@flow
        }

        // Determine destination target directory (dated subfolders if enabled)
        val targetDirectoryDoc = if (config.createDatedFolders) {
            val sampleDate = Date(photosToExport.firstOrNull()?.dateTaken ?: System.currentTimeMillis())
            val yearStr = SimpleDateFormat("yyyy", Locale.US).format(sampleDate)
            val monthDayStr = SimpleDateFormat("MM-dd", Locale.US).format(sampleDate)
            val cleanShoot = config.shootName.ifBlank { "Imported_Shoot" }
                .replace("[^a-zA-Z0-9_.-]".toRegex(), "_")

            val yearDoc = destDoc.findFile(yearStr) ?: destDoc.createDirectory(yearStr) ?: destDoc
            val dateDoc = yearDoc.findFile(monthDayStr) ?: yearDoc.createDirectory(monthDayStr) ?: yearDoc
            dateDoc.findFile(cleanShoot) ?: dateDoc.createDirectory(cleanShoot) ?: dateDoc
        } else {
            destDoc
        }

        val targetPathDisplay = targetDirectoryDoc.name ?: "Destination"

        val total = photosToExport.size
        var totalBytesWritten = 0L
        val exportedNames = mutableListOf<String>()
        val startTime = System.currentTimeMillis()

        emit(ExportProgress(
            isExporting = true,
            currentFileIndex = 0,
            totalFiles = total,
            currentFileName = "Preparing ingest to $targetPathDisplay...",
            targetDirectoryPath = targetPathDisplay
        ))

        for ((index, photo) in photosToExport.withIndex()) {
            val fileNumber = index + 1
            val elapsedSecs = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
            val currentSpeed = (totalBytesWritten / elapsedSecs).toLong()

            emit(ExportProgress(
                isExporting = true,
                currentFileIndex = fileNumber,
                totalFiles = total,
                currentFileName = photo.fileName,
                bytesWritten = totalBytesWritten,
                bytesPerSecond = currentSpeed,
                targetDirectoryPath = targetPathDisplay
            ))

            // 1. Copy RAW file (if selection is BOTH or RAW_ONLY)
            if (config.fileSelection == IngestFileSelection.BOTH_RAW_AND_JPEG ||
                config.fileSelection == IngestFileSelection.RAW_ONLY ||
                photo.companionFileUri == null
            ) {
                val sourceUri = Uri.parse(photo.fileUri)
                val targetRawDoc = targetDirectoryDoc.createFile(exportMimeType(photo.format), photo.fileName)
                if (targetRawDoc != null) {
                    var fileBytes = 0L
                    runCatching {
                        context.contentResolver.openInputStream(sourceUri)?.use { input: InputStream ->
                            context.contentResolver.openOutputStream(targetRawDoc.uri)?.use { output: OutputStream ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    fileBytes += read
                                }
                                output.flush()
                            }
                        }
                    }
                    totalBytesWritten += fileBytes
                }
            }

            // 2. Copy Companion JPEG file (if selection is BOTH or JPEG_ONLY and JPEG exists)
            if ((config.fileSelection == IngestFileSelection.BOTH_RAW_AND_JPEG ||
                 config.fileSelection == IngestFileSelection.JPEG_ONLY) &&
                photo.companionFileUri != null && photo.companionFileName != null
            ) {
                val jpegSourceUri = Uri.parse(photo.companionFileUri)
                val targetJpegDoc = targetDirectoryDoc.createFile(
                    exportMimeType(photo.companionFormat ?: PhotoFormat.JPEG),
                    photo.companionFileName
                )
                if (targetJpegDoc != null) {
                    var jpegBytes = 0L
                    runCatching {
                        context.contentResolver.openInputStream(jpegSourceUri)?.use { input ->
                            context.contentResolver.openOutputStream(targetJpegDoc.uri)?.use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    jpegBytes += read
                                }
                                output.flush()
                            }
                        }
                    }
                    totalBytesWritten += jpegBytes
                }
            }

            // 3. Generate and copy matching Adobe XMP sidecar
            if (config.generateXmpSidecars) {
                val xmpDoc = targetDirectoryDoc.createFile("application/octet-stream", photo.sidecarFileName)
                if (xmpDoc != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(xmpDoc.uri)?.use { output ->
                            val xmpContent = XmpSidecarGenerator.generateXmp(photo)
                            output.write(xmpContent.toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                    }
                }
            }

            exportedNames.add(photo.fileName)
        }

        val totalTime = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
        val finalAvgSpeed = (totalBytesWritten / totalTime).toLong()

        emit(ExportProgress(
            isExporting = false,
            completed = true,
            currentFileIndex = total,
            totalFiles = total,
            currentFileName = "Ingest Completed",
            bytesWritten = totalBytesWritten,
            bytesPerSecond = finalAvgSpeed,
            targetDirectoryPath = targetPathDisplay,
            exportedFileNames = exportedNames
        ))
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** Older SQLite builds allow 999 bound variables per statement. */
        const val MAX_IDS_PER_QUERY = 500
    }
}
