package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.shehran.rawcull.data.model.ImportedFile

@Dao
interface ImportedFileDao {
    /** The size pre-filter: only files of a size seen before are worth hashing. */
    @Query("SELECT DISTINCT sizeBytes FROM imported_files")
    suspend fun knownSizes(): List<Long>

    @Query("SELECT COUNT(*) > 0 FROM imported_files WHERE headHash = :headHash AND sizeBytes = :sizeBytes")
    suspend fun contains(headHash: String, sizeBytes: Long): Boolean

    @Insert
    suspend fun insert(file: ImportedFile): Long
}
