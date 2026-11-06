package dev.shehran.rawcull.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.ExportConfig
import dev.shehran.rawcull.data.model.ExportProgress
import dev.shehran.rawcull.data.model.IngestFileSelection
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.RawMetadataParser
import dev.shehran.rawcull.util.SamplePhotosGenerator
import dev.shehran.rawcull.util.XmpSidecarGenerator
import dev.shehran.rawcull.util.XmpSidecarManager
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
    private val dao: RawPhotoDao,
) {
    fun observeByRoot(rootUri: String): Flow<List<PhotoAsset>> = dao.observeByRoot(rootUri)

    /** Generates the demo shoot once, so flags set on it survive reopening it. */
    suspend fun loadSampleShoot() = withContext(Dispatchers.IO) {
        if (dao.getIdsByRoot(DEMO_ROOT_URI).isEmpty()) {
            dao.upsertAll(SamplePhotosGenerator.createSampleShoot(context))
        }
    }

    suspend fun scanSourceFolder(
        treeUri: Uri,
        onProgress: (scanned: Int, currentName: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        val files = rootDoc.listFiles()
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        ).toString()

        val rawExtensions = setOf("arw", "dng", "cr2", "cr3", "nef", "raw")
        val jpegExtensions = setOf("jpg", "jpeg")

        // Map files by base stem (lowercase)
        val rawDocs = mutableMapOf<String, DocumentFile>()
        val jpegDocs = mutableMapOf<String, DocumentFile>()
        val xmpDocs = mutableMapOf<String, DocumentFile>()

        for (file in files) {
            if (file.isFile) {
                val name = file.name ?: continue
                val stem = name.substringBeforeLast('.').lowercase()
                val ext = name.substringAfterLast('.', "").lowercase()

                when {
                    ext in rawExtensions -> rawDocs[stem] = file
                    ext in jpegExtensions -> jpegDocs[stem] = file
                    ext == "xmp" -> xmpDocs[stem] = file
                }
            }
        }

        val rawItems = mutableListOf<PhotoAsset>()
        var scannedCount = 0

        for ((stem, rawFile) in rawDocs) {
            val name = rawFile.name ?: continue
            scannedCount++
            onProgress(scannedCount, name)

            val format = PhotoFormat.fromFileName(name)
            val metadata = RawMetadataParser.parse(context, rawFile.uri, format)

            // Companion JPEG check
            val matchingJpeg = jpegDocs[stem]
            val pairMode = if (matchingJpeg != null) PairMode.RAW_AND_RASTER else PairMode.RAW_ONLY

            // Existing XMP Sidecar check & pre-population
            val matchingXmp = xmpDocs[stem]
            val xmpData = if (matchingXmp != null) {
                XmpSidecarManager.readSidecar(context, matchingXmp.uri)
            } else null

            val item = PhotoAsset(
                id = AssetIds.of(treeUri.toString(), "", name.substringBeforeLast('.')),
                fileName = name,
                fileUri = rawFile.uri.toString(),
                rootUri = treeUri.toString(),
                parentDocUri = rootDocUri,
                fileLastModified = rawFile.lastModified(),
                metadataLoaded = true,
                format = format,
                fileSizeBytes = rawFile.length(),
                dateTaken = if (metadata.dateTaken > 0) metadata.dateTaken else rawFile.lastModified(),
                cullStatus = xmpData?.cullStatus ?: CullStatus.UNFLAGGED,
                rating = xmpData?.rating ?: 0,
                colorLabel = xmpData?.colorLabel ?: ColorLabel.NONE,
                tags = xmpData?.tags ?: emptyList(),
                width = metadata.width,
                height = metadata.height,
                cameraMake = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                lensModel = metadata.lensModel,
                shutterSpeed = metadata.shutterSpeed,
                aperture = metadata.aperture,
                iso = metadata.iso,
                focalLength = metadata.focalLength,
                exposureBias = metadata.exposureBias,
                orientation = metadata.orientation,
                companionFileName = matchingJpeg?.name,
                companionFileUri = matchingJpeg?.uri?.toString(),
                companionFileSizeBytes = matchingJpeg?.length(),
                companionFormat = matchingJpeg?.let { PhotoFormat.JPEG },
                xmpFileUri = matchingXmp?.uri?.toString(),
                pairMode = pairMode,
                xmpSyncStatus = XmpSyncStatus.SYNCED
            )
            rawItems.add(item)
        }

        if (rawItems.isNotEmpty()) {
            dao.upsertAll(rawItems)
        }
        rawItems.size
    }

    /** `.HIF` isn't an extension providers map to image/heif, so they would append one. */
    private fun exportMimeType(format: PhotoFormat): String =
        if (format == PhotoFormat.HEIF) "application/octet-stream" else format.mimeType

    private suspend fun syncXmpOnDisk(photoId: String) {
        val photo = dao.getPhotoById(photoId) ?: return
        val xmpUri = XmpSidecarManager.writeSidecar(context, photo)
        val status = if (xmpUri != null) XmpSyncStatus.SYNCED else XmpSyncStatus.ERROR
        val storedUri = xmpUri ?: photo.xmpFileUri
        if (storedUri != photo.xmpFileUri || status != photo.xmpSyncStatus) {
            dao.updateXmpState(photoId, storedUri, status)
        }
    }

    suspend fun setCullStatus(id: String, status: CullStatus) = withContext(Dispatchers.IO) {
        dao.updateCullStatus(id, status)
        syncXmpOnDisk(id)
    }

    suspend fun setRating(id: String, rating: Int) = withContext(Dispatchers.IO) {
        dao.updateRating(id, rating.coerceIn(0, 5))
        syncXmpOnDisk(id)
    }

    suspend fun setColorLabel(id: String, label: ColorLabel) = withContext(Dispatchers.IO) {
        dao.updateColorLabel(id, label)
        syncXmpOnDisk(id)
    }

    suspend fun updatePhoto(photo: PhotoAsset) = withContext(Dispatchers.IO) {
        dao.updatePhoto(photo)
        syncXmpOnDisk(photo.id)
    }

    suspend fun batchSetCullStatus(ids: List<String>, status: CullStatus) = withContext(Dispatchers.IO) {
        ids.forEach {
            dao.updateCullStatus(it, status)
            syncXmpOnDisk(it)
        }
    }

    suspend fun batchSetRating(ids: List<String>, rating: Int) = withContext(Dispatchers.IO) {
        ids.forEach {
            dao.updateRating(it, rating)
            syncXmpOnDisk(it)
        }
    }

    suspend fun batchSetColorLabel(ids: List<String>, label: ColorLabel) = withContext(Dispatchers.IO) {
        ids.forEach {
            dao.updateColorLabel(it, label)
            syncXmpOnDisk(it)
        }
    }

    suspend fun batchAddTag(ids: List<String>, tag: String) = withContext(Dispatchers.IO) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return@withContext
        ids.forEach { id ->
            val photo = dao.getPhotoById(id)
            if (photo != null && !photo.tags.contains(trimmed)) {
                val updated = photo.copy(tags = photo.tags + trimmed)
                dao.updatePhoto(updated)
                syncXmpOnDisk(id)
            }
        }
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
}
