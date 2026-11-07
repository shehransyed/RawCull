package dev.shehran.rawcull.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ImportJobStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
    SOURCE_LOST;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isResumable: Boolean get() = this == PAUSED || this == SOURCE_LOST
}

enum class VerifyMode {
    FULL,
    QUICK,
}

@Entity(tableName = "import_jobs")
data class ImportJob(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val status: ImportJobStatus = ImportJobStatus.QUEUED,
    val sourceTreeUri: String,
    val sourceLabel: String,
    val locationId: Long,
    val template: String, // The resolved pattern, for display
    val verifyMode: VerifyMode = VerifyMode.FULL,
    val deleteSourceAfter: Boolean = false,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val doneBytes: Long = 0,
    val skippedFiles: Int = 0,
    val failedFiles: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val activeMillis: Long = 0, // Time spent running, so pauses count towards neither elapsed time nor speed
)

enum class ImportItemState {
    QUEUED,
    COPYING,
    VERIFYING,
    DONE,
    SKIPPED_DUPLICATE,
    FAILED,
}

/** What a file is to its asset, so a library row can be rebuilt from items after process death. */
enum class ImportItemRole {
    PRIMARY,
    COMPANION,
    ADOBE_SIDECAR,
    DARKTABLE_SIDECAR;

    val isSidecar: Boolean get() = this == ADOBE_SIDECAR || this == DARKTABLE_SIDECAR
}

@Entity(
    tableName = "import_job_items",
    foreignKeys = [
        ForeignKey(
            entity = ImportJob::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("jobId")],
)
data class ImportJobItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val jobId: Long,
    val groupKey: String, // Asset group, so a suffix or a delete applies to all of its files
    val sourceUri: String,
    val sourceName: String,
    val sizeBytes: Long,
    val targetRelativeDir: String,
    val targetName: String,
    val targetUri: String? = null,
    val state: ImportItemState = ImportItemState.QUEUED,
    val error: String? = null,
    val headHash: String? = null,
    val fullHash: String? = null,
    val sourceDeleted: Boolean = false,
    val role: ImportItemRole = ImportItemRole.PRIMARY,
    val captureDate: Long = 0L,
    val metadataJson: String? = null, // On the primary: the EXIF read during the card scan
    val assetDiscriminator: String? = null, // On the primary: see AssetIds
)
