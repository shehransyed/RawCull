package com.example.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.data.model.RawFormat
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class ExtractedRawMetadata(
    val cameraMake: String?,
    val cameraModel: String?,
    val lensModel: String?,
    val shutterSpeed: String?,
    val aperture: String?,
    val iso: String?,
    val focalLength: String?,
    val exposureBias: String?,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val orientation: Int
)

object RawMetadataParser {

    fun parse(context: Context, uri: Uri, fallbackFormat: RawFormat): ExtractedRawMetadata {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                parseFromStream(inputStream)
            }
        }.getOrNull() ?: fallbackMetadata(fallbackFormat)
    }

    fun parseFromStream(inputStream: InputStream): ExtractedRawMetadata {
        val exif = ExifInterface(inputStream)

        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()

        val shutter = formatShutterSpeed(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        val aperture = formatAperture(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
        val focalLength = formatFocalLength(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
        val exposureBias = formatExposureBias(exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE))

        val dateTaken = parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME))
            ?: System.currentTimeMillis()

        var width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        var height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        // Swap width & height if rotated 90 or 270 deg
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
            val tmp = width
            width = height
            height = tmp
        }

        return ExtractedRawMetadata(
            cameraMake = make,
            cameraModel = model,
            lensModel = lens,
            shutterSpeed = shutter,
            aperture = aperture,
            iso = iso,
            focalLength = focalLength,
            exposureBias = exposureBias,
            dateTaken = dateTaken,
            width = width,
            height = height,
            orientation = orientation
        )
    }

    private fun formatShutterSpeed(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val seconds = value.toDoubleOrNull() ?: return value
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else if (seconds > 0.0) {
            val denom = (1.0 / seconds).toInt()
            "1/${denom}s"
        } else {
            value
        }
    }

    private fun formatAperture(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val fNum = value.toDoubleOrNull() ?: return "f/$value"
        return String.format(Locale.US, "f/%.1f", fNum).replace(".0", "")
    }

    private fun formatFocalLength(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val mm = value.toDoubleOrNull() ?: return "${value}mm"
        return String.format(Locale.US, "%.0f mm", mm)
    }

    private fun formatExposureBias(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val ev = value.toDoubleOrNull() ?: return value
        return if (ev > 0) String.format(Locale.US, "+%.1f EV", ev)
        else if (ev < 0) String.format(Locale.US, "%.1f EV", ev)
        else "0 EV"
    }

    private fun parseDateTime(dateTimeStr: String?): Long? {
        if (dateTimeStr.isNullOrBlank()) return null
        val formats = arrayOf(
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy:MM:dd", Locale.US)
        )
        for (format in formats) {
            runCatching {
                return format.parse(dateTimeStr)?.time
            }
        }
        return null
    }

    private fun fallbackMetadata(format: RawFormat): ExtractedRawMetadata {
        val defaultModel = when (format) {
            RawFormat.ARW -> "Sony Alpha (RAW)"
            RawFormat.DNG -> "Adobe DNG Digital Negative"
            RawFormat.CR2, RawFormat.CR3 -> "Canon EOS (RAW)"
            RawFormat.NEF -> "Nikon NEF (RAW)"
            else -> "Digital RAW Camera"
        }
        return ExtractedRawMetadata(
            cameraMake = null,
            cameraModel = defaultModel,
            lensModel = null,
            shutterSpeed = null,
            aperture = null,
            iso = null,
            focalLength = null,
            exposureBias = null,
            dateTaken = System.currentTimeMillis(),
            width = 0,
            height = 0,
            orientation = ExifInterface.ORIENTATION_NORMAL
        )
    }
}
