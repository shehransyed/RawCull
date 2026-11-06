package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.EnrichedMetadata
import dev.shehran.rawcull.data.model.EnrichmentResult
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPhotoDao {
    @Query("SELECT * FROM photo_assets WHERE rootUri = :rootUri ORDER BY dateTaken ASC, fileName ASC")
    fun observeByRoot(rootUri: String): Flow<List<PhotoAsset>>

    @Query("SELECT * FROM photo_assets WHERE rootUri = :rootUri ORDER BY dateTaken ASC, fileName ASC")
    suspend fun getByRoot(rootUri: String): List<PhotoAsset>

    @Query("SELECT id FROM photo_assets WHERE rootUri = :rootUri")
    suspend fun getIdsByRoot(rootUri: String): List<String>

    @Query("SELECT * FROM photo_assets WHERE id = :id LIMIT 1")
    suspend fun getPhotoById(id: String): PhotoAsset?

    @Upsert
    suspend fun upsertAll(photos: List<PhotoAsset>)

    @Query("DELETE FROM photo_assets WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Update
    suspend fun updatePhoto(photo: PhotoAsset)

    /**
     * Writes enrichment results. Only metadata columns are touched, and XMP state goes through
     * [applyXmpIfNewer], so a flag set while enrichment was running is never overwritten.
     */
    @Transaction
    suspend fun updateMetadataBatch(results: List<EnrichmentResult>) {
        applyMetadata(results.map { it.metadata })
        results.forEach { result ->
            result.xmp?.let { xmp ->
                applyXmpIfNewer(
                    id = result.metadata.id,
                    rating = xmp.rating,
                    cullStatus = xmp.cullStatus,
                    colorLabel = xmp.colorLabel,
                    tagsJson = xmp.tagsJson,
                    xmpLastModified = xmp.lastModified,
                )
            }
        }
    }

    @Update(entity = PhotoAsset::class)
    suspend fun applyMetadata(metadata: List<EnrichedMetadata>)

    /** XMP wins only when it is newer than the row's last change; new rows have lastModified = 0. */
    @Query(
        """
        UPDATE photo_assets SET
            rating = COALESCE(:rating, rating),
            cullStatus = COALESCE(:cullStatus, cullStatus),
            colorLabel = COALESCE(:colorLabel, colorLabel),
            tags = :tagsJson,
            lastModified = :xmpLastModified
        WHERE id = :id AND lastModified < :xmpLastModified
        """
    )
    suspend fun applyXmpIfNewer(
        id: String,
        rating: Int?,
        cullStatus: CullStatus?,
        colorLabel: ColorLabel?,
        tagsJson: String,
        xmpLastModified: Long,
    )

    @Query("UPDATE photo_assets SET cullStatus = :status, lastModified = :time WHERE id = :id")
    suspend fun updateCullStatus(id: String, status: CullStatus, time: Long = System.currentTimeMillis())

    @Query("UPDATE photo_assets SET rating = :rating, lastModified = :time WHERE id = :id")
    suspend fun updateRating(id: String, rating: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE photo_assets SET colorLabel = :label, lastModified = :time WHERE id = :id")
    suspend fun updateColorLabel(id: String, label: ColorLabel, time: Long = System.currentTimeMillis())

    @Query("UPDATE photo_assets SET xmpFileUri = :xmpFileUri, xmpSyncStatus = :status WHERE id = :id")
    suspend fun updateXmpState(id: String, xmpFileUri: String?, status: XmpSyncStatus)
}
