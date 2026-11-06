package dev.shehran.rawcull.data.model

data class FilterOptions(
    val cullFilter: CullFilterMode = CullFilterMode.ALL,
    val minRating: Int = 0, // 0 = all, 1..5
    val exactRating: Int? = null,
    val colorFilter: ColorLabel? = null,
    val formatFilter: RawFormat? = null,
    val pairFilter: PairMode? = null,
    val tagFilter: String? = null,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.DATE_ASC
)

enum class CullFilterMode(val label: String) {
    ALL("All Photos"),
    PICKS_ONLY("Picks Only"),
    UNFLAGGED_ONLY("Unflagged"),
    REJECTS_ONLY("Rejects"),
    PICKS_AND_UNFLAGGED("Picks & Unflagged")
}

enum class SortOrder(val label: String) {
    DATE_ASC("Capture Time (Oldest)"),
    DATE_DESC("Capture Time (Newest)"),
    NAME_ASC("File Name (A-Z)"),
    NAME_DESC("File Name (Z-A)"),
    RATING_DESC("Rating (Highest)"),
    SIZE_DESC("File Size (Largest)")
}

enum class IngestFileSelection(val label: String, val description: String) {
    BOTH_RAW_AND_JPEG("RAW + JPEG", "Copy both original RAW and companion JPEG"),
    RAW_ONLY("RAW Only", "Copy only the original RAW file and skip companion JPEG"),
    JPEG_ONLY("JPEG Only", "Copy only companion JPEG file and skip RAW")
}

data class ExportConfig(
    val destinationFolderUri: String,
    val shootName: String = "Imported_Shoot",
    val createDatedFolders: Boolean = true,
    val fileSelection: IngestFileSelection = IngestFileSelection.BOTH_RAW_AND_JPEG,
    val exportMode: ExportMode = ExportMode.PICKS_ONLY,
    val minRating: Int = 0,
    val generateXmpSidecars: Boolean = true,
    val overwriteExisting: Boolean = false
)

enum class ExportMode(val label: String, val description: String) {
    PICKS_ONLY("Picks Only", "Export only photos flagged as Pick"),
    PICKS_OR_RATED("Picks or Rated", "Export photos flagged as Pick or with rating >= threshold"),
    ALL_FLAGGED("All Flagged", "Export all rated, labeled, or flagged photos"),
    CUSTOM_FILTER("Current View", "Export all photos currently matching active filters")
}

data class ExportProgress(
    val isExporting: Boolean = false,
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val bytesWritten: Long = 0,
    val bytesPerSecond: Long = 0,
    val targetDirectoryPath: String = "",
    val errorMessage: String? = null,
    val completed: Boolean = false,
    val exportedFileNames: List<String> = emptyList()
) {
    val formattedSpeed: String
        get() {
            val mbPerSec = bytesPerSecond / (1024.0 * 1024.0)
            return if (mbPerSec >= 0.1) String.format("%.1f MB/s", mbPerSec) else "${bytesPerSecond / 1024} KB/s"
        }
}
