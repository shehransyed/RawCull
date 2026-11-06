package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.example.data.model.RawFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object RawPreviewLoader {
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8 // Use 1/8th of available memory
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    suspend fun loadPreview(
        context: Context,
        uri: Uri,
        id: String,
        format: RawFormat,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        companionJpegUri: Uri? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${id}_${targetWidth}x${targetHeight}"
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // Check disk cache
        val diskCached = getFromDiskCache(context, cacheKey)
        if (diskCached != null) {
            memoryCache.put(cacheKey, diskCached)
            return@withContext diskCached
        }

        // Decode from companion JPEG if available, or RAW source
        val bitmap = (if (companionJpegUri != null) decodeWithBitmapFactory(context, companionJpegUri, targetWidth, targetHeight)?.let { applyExifOrientation(context, companionJpegUri, it) } else null)
            ?: decodeRaw(context, uri, format, targetWidth, targetHeight)

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
            saveToDiskCache(context, cacheKey, bitmap)
        }
        bitmap
    }

    suspend fun loadThumbnail(
        context: Context,
        uri: Uri,
        id: String,
        format: RawFormat,
        maxDimension: Int = 512,
        companionJpegUri: Uri? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${id}_thumb_${maxDimension}"
        memoryCache.get(cacheKey)?.let { return@withContext it }

        val diskCached = getFromDiskCache(context, cacheKey)
        if (diskCached != null) {
            memoryCache.put(cacheKey, diskCached)
            return@withContext diskCached
        }

        val bitmap = (if (companionJpegUri != null) decodeWithBitmapFactory(context, companionJpegUri, maxDimension, maxDimension)?.let { applyExifOrientation(context, companionJpegUri, it) } else null)
            ?: decodeRaw(context, uri, format, maxDimension, maxDimension)

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
            saveToDiskCache(context, cacheKey, bitmap)
        }
        bitmap
    }

    private fun decodeRaw(
        context: Context,
        uri: Uri,
        format: RawFormat,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        // Strategy 1: Extract embedded JPEG thumbnail/preview from ExifInterface
        var bitmap = extractExifThumbnail(context, uri)

        // Strategy 2: If format is DNG or ARW and ExifInterface thumbnail is small/null, use ImageDecoder (API 28+)
        if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bitmap = decodeWithImageDecoder(context, uri, targetW, targetH)
        }

        // Strategy 3: Scan raw byte stream for embedded JPEG marker (common in Sony ARW / DNG)
        if (bitmap == null) {
            bitmap = extractEmbeddedJpegStream(context, uri, targetW, targetH)
        }

        // Strategy 4: Standard BitmapFactory fallback with downsampling
        if (bitmap == null) {
            bitmap = decodeWithBitmapFactory(context, uri, targetW, targetH)
        }

        // Rotate according to EXIF orientation
        return bitmap?.let { applyExifOrientation(context, uri, it) }
    }

    private fun extractExifThumbnail(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val thumbBytes = exif.thumbnailBytes
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                } else {
                    exif.thumbnailBitmap
                }
            }
        }.getOrNull()
    }

    private fun decodeWithImageDecoder(
        context: Context,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val origW = info.size.width
                val origH = info.size.height
                if (origW > targetW || origH > targetH) {
                    val sample = calculateInSampleSize(origW, origH, targetW, targetH)
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun extractEmbeddedJpegStream(
        context: Context,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // Search for JPEG SOI (0xFF, 0xD8, 0xFF) within the first 16MB of the RAW file
                val buffer = ByteArray(64 * 1024)
                var bytesRead = stream.read(buffer)
                var totalScanned = 0
                val maxScan = 16 * 1024 * 1024

                // Find 0xFF 0xD8 0xFF
                var soiOffset = -1
                for (i in 0 until bytesRead - 2) {
                    if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xD8.toByte() && buffer[i + 2] == 0xFF.toByte()) {
                        soiOffset = i
                        break
                    }
                }

                if (soiOffset >= 0) {
                    // Read subsequent bytes into a ByteArrayOutputStream or direct byte array
                    val remainingData = java.io.ByteArrayOutputStream()
                    remainingData.write(buffer, soiOffset, bytesRead - soiOffset)

                    // Read up to 8MB of JPEG payload
                    val chunk = ByteArray(64 * 1024)
                    var extraRead = 0
                    while (extraRead < 8 * 1024 * 1024) {
                        val n = stream.read(chunk)
                        if (n <= 0) break
                        remainingData.write(chunk, 0, n)
                        extraRead += n
                    }

                    val jpegBytes = remainingData.toByteArray()
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        options.inJustDecodeBounds = false
                        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, targetW, targetH)
                        return@use BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun decodeWithBitmapFactory(
        context: Context,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        return runCatching {
            var options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            val sample = calculateInSampleSize(options.outWidth, options.outHeight, targetW, targetH)
            options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sample
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getDiskCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "lumina_raw_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getFromDiskCache(context: Context, key: String): Bitmap? {
        val sanitized = key.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val file = File(getDiskCacheDir(context), "$sanitized.jpg")
        if (file.exists() && file.length() > 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        return null
    }

    private fun saveToDiskCache(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            val sanitized = key.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val file = File(getDiskCacheDir(context), "$sanitized.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }
    }

    fun clearCache(context: Context) {
        memoryCache.evictAll()
        runCatching {
            getDiskCacheDir(context).deleteRecursively()
        }
    }
}
