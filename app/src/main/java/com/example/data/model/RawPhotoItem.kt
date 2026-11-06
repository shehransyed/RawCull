package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CullStatus {
    UNFLAGGED,
    PICK,
    REJECT
}

enum class ColorLabel(val hexColor: Long, val labelName: String) {
    NONE(0x00000000, "None"),
    RED(0xFFEF4444, "Red (Select)"),
    YELLOW(0xFFF59E0B, "Yellow (Review)"),
    GREEN(0xFF10B981, "Green (Approved)"),
    BLUE(0xFF3B82F6, "Blue (Work)"),
    PURPLE(0xFF8B5CF6, "Purple (Special)")
}

enum class RawFormat(val extension: String, val displayName: String) {
    ARW("ARW", "Sony ARW"),
    DNG("DNG", "Adobe DNG"),
    CR2("CR2", "Canon CR2"),
    CR3("CR3", "Canon CR3"),
    NEF("NEF", "Nikon NEF"),
    RAW_OTHER("RAW", "Raw Image");

    companion object {
        fun fromFileName(name: String): RawFormat {
            val ext = name.substringAfterLast('.', "").uppercase()
            return when (ext) {
                "ARW" -> ARW
                "DNG" -> DNG
                "CR2" -> CR2
                "CR3" -> CR3
                "NEF" -> NEF
                else -> RAW_OTHER
            }
        }
    }
}

enum class PairMode(val badgeLabel: String, val displayName: String) {
    RAW_ONLY("RAW", "RAW Only"),
    JPEG_ONLY("JPG", "JPEG Only"),
    RAW_AND_JPEG("RAW+JPG", "Stacked RAW + JPEG")
}

enum class XmpSyncStatus {
    SYNCED,
    WRITING,
    ERROR
}

@Entity(tableName = "raw_photos")
data class RawPhotoItem(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val fileUri: String,
    val folderUri: String,
    val format: RawFormat,
    val fileSizeBytes: Long,
    val dateTaken: Long,
    val cullStatus: CullStatus = CullStatus.UNFLAGGED,
    val rating: Int = 0, // 0 to 5
    val colorLabel: ColorLabel = ColorLabel.NONE,
    val tags: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val shutterSpeed: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val exposureBias: String? = null,
    val orientation: Int = 0,
    val cachedThumbnailPath: String? = null,
    val isSample: Boolean = false,
    val lastModified: Long = System.currentTimeMillis(),

    // Companion JPEG & XMP Pairing Fields
    val jpegFileName: String? = null,
    val jpegFileUri: String? = null,
    val jpegFileSizeBytes: Long? = null,
    val xmpFileUri: String? = null,
    val pairMode: PairMode = PairMode.RAW_ONLY,
    val xmpSyncStatus: XmpSyncStatus = XmpSyncStatus.SYNCED
) {
    val hasCompanionJpeg: Boolean
        get() = !jpegFileUri.isNullOrBlank()

    val baseStem: String
        get() = fileName.substringBeforeLast('.')

    val formattedFileSize: String
        get() {
            val totalBytes = fileSizeBytes + (jpegFileSizeBytes ?: 0L)
            val mb = totalBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${totalBytes / 1024} KB"
        }

    val displayExposure: String
        get() {
            val parts = mutableListOf<String>()
            shutterSpeed?.let { parts.add(it) }
            aperture?.let { parts.add(it) }
            iso?.let { parts.add("ISO $it") }
            focalLength?.let { parts.add(it) }
            return if (parts.isNotEmpty()) parts.joinToString("  •  ") else "No EXIF"
        }
}
