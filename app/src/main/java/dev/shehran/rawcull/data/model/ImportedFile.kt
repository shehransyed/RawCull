package dev.shehran.rawcull.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The duplicate index: one row per image file RawCull has copied into a library. */
@Entity(
    tableName = "imported_files",
    indices = [Index(value = ["headHash", "sizeBytes"]), Index("sizeBytes")],
)
data class ImportedFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val headHash: String, // SHA-256 hex of the first 64 KB
    val sizeBytes: Long,
    val originalName: String,
    val targetUri: String,
    val importedAt: Long,
    val jobId: Long? = null,
)
