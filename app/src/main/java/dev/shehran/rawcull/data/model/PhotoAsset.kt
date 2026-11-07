package dev.shehran.rawcull.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

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

/** Root of the bundled demo shoot; it is a pseudo-folder and never mixes with real catalogs. */
const val DEMO_ROOT_URI = "rawcull-demo://sample"

enum class PhotoFormat(
    val extension: String,
    val displayName: String,
    val isRaw: Boolean,
    val mimeType: String,
) {
    ARW("ARW", "Sony ARW", true, "image/x-sony-arw"),
    DNG("DNG", "Adobe DNG", true, "image/x-adobe-dng"),
    CR2("CR2", "Canon CR2", true, "image/x-canon-cr2"),
    CR3("CR3", "Canon CR3", true, "image/x-canon-cr3"),
    NEF("NEF", "Nikon NEF", true, "image/x-nikon-nef"),
    NRW("NRW", "Nikon NRW", true, "image/x-nikon-nrw"),
    RAF("RAF", "Fujifilm RAF", true, "image/x-fuji-raf"),
    ORF("ORF", "Olympus ORF", true, "image/x-olympus-orf"),
    RW2("RW2", "Panasonic RW2", true, "image/x-panasonic-rw2"),
    PEF("PEF", "Pentax PEF", true, "image/x-pentax-pef"),
    SRW("SRW", "Samsung SRW", true, "image/x-samsung-srw"),
    RAW_OTHER("RAW", "Raw Image", true, "application/octet-stream"),
    JPEG("JPG", "JPEG", false, "image/jpeg"),
    HEIF("HIF", "HEIF", false, "image/heif");

    companion object {
        private val byExtension: Map<String, PhotoFormat> =
            entries.associateBy { it.extension } + mapOf("JPEG" to JPEG, "HEIC" to HEIF, "HEIF" to HEIF)

        /** The format for a recognised extension, or null for anything else (video, .THM, .xmp). */
        fun fromFileNameOrNull(name: String): PhotoFormat? {
            if ('.' !in name) return null
            return byExtension[name.substringAfterLast('.').uppercase(Locale.ROOT)]
        }

        fun fromFileName(name: String): PhotoFormat = fromFileNameOrNull(name) ?: RAW_OTHER
    }
}

enum class PairMode {
    RAW_ONLY,
    RASTER_ONLY,
    RAW_AND_RASTER
}

enum class XmpSyncStatus {
    SYNCED,
    PENDING,
    WRITING,
    ERROR,
    READ_ONLY
}

@Entity(
    tableName = "photo_assets",
    indices = [Index("rootUri"), Index(value = ["rootUri", "relativeDir"])],
)
data class PhotoAsset(
    @PrimaryKey
    val id: String,
    val rootUri: String, // The picked tree URI (the "source")
    val relativeDir: String = "", // Containing folder relative to the root, e.g. DCIM/100MSDCF
    val parentDocUri: String = "", // Containing directory, so sidecars are created without findFile

    // The primary file: the RAW if there is one, otherwise the raster file
    val fileName: String,
    val fileUri: String,
    val format: PhotoFormat,
    val fileSizeBytes: Long,
    val fileLastModified: Long = 0L,
    val dateTaken: Long, // fileLastModified until enrichment replaces it with EXIF DateTimeOriginal
    val metadataLoaded: Boolean = false,

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
    val lastModified: Long = System.currentTimeMillis(),

    // Companion raster & XMP pairing fields
    val companionFileName: String? = null,
    val companionFileUri: String? = null,
    val companionFileSizeBytes: Long? = null,
    val companionFormat: PhotoFormat? = null,
    val xmpFileUri: String? = null,
    val pairMode: PairMode = PairMode.RAW_ONLY,
    val xmpSyncStatus: XmpSyncStatus = XmpSyncStatus.SYNCED,
    val xmpLastSyncedMtime: Long? = null, // The sidecar's modification time when the row last matched it
    val xmpError: String? = null,
    val darktableXmpUri: String? = null, // <name>.<ext>.xmp, kept up to date alongside xmpFileUri
    val xmpPendingFields: Int = 0, // XmpField bits changed in RawCull and not on disk yet
    val lastImportJobId: Long? = null, // The import that last brought this asset into the library
) {
    val hasCompanion: Boolean
        get() = !companionFileUri.isNullOrBlank()

    val baseStem: String
        get() = fileName.substringBeforeLast('.')

    /** Ids are stable across rescans, so previews must also be keyed on the file's version. */
    val previewCacheKey: String
        get() = "${id}_$fileLastModified"

    /**
     * Name for a new sidecar. `<stem>.xmp` belongs to the first asset of a stem group, so assets
     * that share that stem (see [AssetIds]) use the darktable-style `<name>.<ext>.xmp` instead of
     * overwriting it.
     */
    val sidecarFileName: String
        get() = if (id == AssetIds.of(rootUri, relativeDir, baseStem)) "$baseStem.xmp" else "$fileName.xmp"

    val formattedFileSize: String
        get() {
            val totalBytes = fileSizeBytes + (companionFileSizeBytes ?: 0L)
            val mb = totalBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else "${totalBytes / 1024} KB"
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
