package dev.shehran.rawcull.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.shehran.rawcull.data.model.GeoPoint
import dev.shehran.rawcull.data.model.PhotoFormat
import java.io.FileDescriptor
import java.io.FileNotFoundException
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
    val dateTaken: Long?, // Null when the file doesn't say; callers keep their own fallback
    val width: Int,
    val height: Int,
    val orientation: Int,
    val parsed: Boolean = true, // False for fallback values that weren't read from the file
    val location: GeoPoint? = null, // The EXIF GPS position; none for a (0, 0) fix, which cameras write for "no fix"
)

object RawMetadataParser {

    /**
     * Reads headers through a seekable descriptor, so the whole file isn't streamed. The companion
     * is the EXIF source when the primary isn't parseable (CR3, unknown RAW).
     *
     * A file that can't be opened throws, because that may mean the volume is gone. A file that
     * opens but can't be parsed yields fallback metadata with [ExtractedRawMetadata.parsed] false.
     */
    fun parse(context: Context, uri: Uri, format: PhotoFormat, companionUri: Uri? = null): ExtractedRawMetadata {
        val primary = parseDocument(context, uri)
        if (primary != null && primary.hasCameraData) return primary
        val companion = companionUri?.let { parseDocument(context, it) }
        if (companion != null && companion.hasCameraData) return companion
        return primary ?: fallbackMetadata(format)
    }

    private fun parseDocument(context: Context, uri: Uri): ExtractedRawMetadata? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("No descriptor for $uri")
        return descriptor.use {
            runCatching { parseFromDescriptor(it.fileDescriptor) }.getOrNull()
        }
    }

    private val ExtractedRawMetadata.hasCameraData: Boolean
        get() = cameraMake != null || cameraModel != null || dateTaken != null

    fun parseFromDescriptor(descriptor: FileDescriptor): ExtractedRawMetadata =
        extract(ExifInterface(descriptor))

    fun parseFromStream(inputStream: InputStream): ExtractedRawMetadata =
        extract(ExifInterface(inputStream))

    private fun extract(exif: ExifInterface): ExtractedRawMetadata {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()

        val shutter = formatShutterSpeed(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        val aperture = formatAperture(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            ?: @Suppress("DEPRECATION") exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
        val focalLength = formatFocalLength(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
        val exposureBias = formatExposureBias(exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE))

        val dateTaken = parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME))

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
            orientation = orientation,
            location = location(exif),
        )
    }

    private fun location(exif: ExifInterface): GeoPoint? {
        val latLong = exif.latLong ?: return null
        if (latLong[0] == 0.0 && latLong[1] == 0.0) return null
        val altitude = exif.getAltitude(Double.NaN).takeUnless { it.isNaN() }
        return GeoPoint(latLong[0], latLong[1], altitude)
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

    private fun fallbackMetadata(format: PhotoFormat): ExtractedRawMetadata {
        val defaultModel = when (format) {
            PhotoFormat.ARW -> "Sony Alpha (RAW)"
            PhotoFormat.DNG -> "Adobe DNG Digital Negative"
            PhotoFormat.CR2, PhotoFormat.CR3 -> "Canon EOS (RAW)"
            PhotoFormat.NEF, PhotoFormat.NRW -> "Nikon NEF (RAW)"
            PhotoFormat.JPEG, PhotoFormat.HEIF -> null
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
            dateTaken = null,
            width = 0,
            height = 0,
            orientation = ExifInterface.ORIENTATION_NORMAL,
            parsed = false,
        )
    }
}
