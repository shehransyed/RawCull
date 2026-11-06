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
import dev.shehran.rawcull.data.xmp.XMP_PARSE_ERROR
import dev.shehran.rawcull.data.xmp.XmpRows
import dev.shehran.rawcull.util.xmp.CullState
import dev.shehran.rawcull.util.xmp.XmpField
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPhotoDao : XmpRows {
    @Query("SELECT * FROM photo_assets WHERE rootUri = :rootUri ORDER BY dateTaken ASC, fileName ASC")
    fun observeByRoot(rootUri: String): Flow<List<PhotoAsset>>

    @Query("SELECT * FROM photo_assets WHERE rootUri = :rootUri ORDER BY dateTaken ASC, fileName ASC")
    suspend fun getByRoot(rootUri: String): List<PhotoAsset>

    @Query("SELECT id FROM photo_assets WHERE rootUri = :rootUri")
    suspend fun getIdsByRoot(rootUri: String): List<String>

    @Query("SELECT * FROM photo_assets WHERE id = :id LIMIT 1")
    override suspend fun getPhotoById(id: String): PhotoAsset?

    @Upsert
    suspend fun upsertAll(photos: List<PhotoAsset>)

    @Query("DELETE FROM photo_assets WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Writes enrichment results. Only metadata columns are touched, and sidecar state goes through
     * [applyXmpFromFile], so a flag set while enrichment was running is never overwritten.
     */
    @Transaction
    suspend fun updateMetadataBatch(results: List<EnrichmentResult>) {
        applyMetadata(results.map { it.metadata })
        results.forEach { result ->
            result.xmp?.let { xmp ->
                applyXmpFromFile(
                    id = result.metadata.id,
                    rating = xmp.rating,
                    cullStatus = xmp.cullStatus,
                    colorLabel = xmp.colorLabel,
                    tagsJson = xmp.tagsJson,
                    mtime = xmp.lastModified,
                )
            }
            if (result.sidecarUnreadable) markSidecarUnreadable(result.metadata.id, XMP_PARSE_ERROR)
        }
    }

    @Update(entity = PhotoAsset::class)
    suspend fun applyMetadata(metadata: List<EnrichedMetadata>)

    /** The file wins unless the row has edits of its own, which the queue merges into the file. */
    @Query(
        """
        UPDATE photo_assets SET
            rating = :rating,
            cullStatus = COALESCE(:cullStatus, cullStatus),
            colorLabel = :colorLabel,
            tags = :tagsJson,
            xmpLastSyncedMtime = :mtime,
            xmpSyncStatus = 'SYNCED',
            xmpError = NULL
        WHERE id = :id AND xmpSyncStatus NOT IN ('PENDING', 'WRITING') AND xmpPendingFields = 0
        """
    )
    suspend fun applyXmpFromFile(
        id: String,
        rating: Int,
        cullStatus: CullStatus?,
        colorLabel: ColorLabel,
        tagsJson: String,
        mtime: Long,
    )

    /** The queue reports this itself for rows it is about to write. */
    @Query(
        """
        UPDATE photo_assets SET xmpSyncStatus = 'ERROR', xmpError = :error
        WHERE id = :id AND xmpSyncStatus NOT IN ('PENDING', 'WRITING')
        """
    )
    suspend fun markSidecarUnreadable(id: String, error: String)

    @Query(
        """
        UPDATE photo_assets SET cullStatus = :status, lastModified = :time,
            xmpSyncStatus = 'PENDING', xmpPendingFields = xmpPendingFields | :pendingBit
        WHERE id IN (:ids)
        """
    )
    suspend fun updateCullStatus(
        ids: List<String>,
        status: CullStatus,
        time: Long = System.currentTimeMillis(),
        pendingBit: Int = XmpField.PICK.bit,
    )

    @Query(
        """
        UPDATE photo_assets SET rating = :rating, lastModified = :time,
            xmpSyncStatus = 'PENDING', xmpPendingFields = xmpPendingFields | :pendingBit
        WHERE id IN (:ids)
        """
    )
    suspend fun updateRating(
        ids: List<String>,
        rating: Int,
        time: Long = System.currentTimeMillis(),
        pendingBit: Int = XmpField.RATING.bit,
    )

    @Query(
        """
        UPDATE photo_assets SET colorLabel = :label, lastModified = :time,
            xmpSyncStatus = 'PENDING', xmpPendingFields = xmpPendingFields | :pendingBit
        WHERE id IN (:ids)
        """
    )
    suspend fun updateColorLabel(
        ids: List<String>,
        label: ColorLabel,
        time: Long = System.currentTimeMillis(),
        pendingBit: Int = XmpField.LABEL.bit,
    )

    @Query(
        """
        UPDATE photo_assets SET tags = :tagsJson, lastModified = :time,
            xmpSyncStatus = 'PENDING', xmpPendingFields = xmpPendingFields | :pendingBit
        WHERE id = :id
        """
    )
    suspend fun updateTags(
        id: String,
        tagsJson: String,
        time: Long = System.currentTimeMillis(),
        pendingBit: Int = XmpField.TAGS.bit,
    )

    @Query("UPDATE photo_assets SET xmpSyncStatus = 'WRITING' WHERE id = :id")
    override suspend fun markWriting(id: String)

    @Transaction
    override suspend fun finishSynced(id: String, xmpFileUri: String?, mtime: Long?, fileState: CullState?) {
        recordSidecar(id, xmpFileUri, mtime)
        if (fileState != null) {
            applyFileState(
                id = id,
                rating = fileState.rating,
                cullStatus = fileState.cullStatus,
                colorLabel = fileState.colorLabel,
                tagsJson = Converters().fromTagsList(fileState.tags),
            )
        }
        markSynced(id)
    }

    @Query("UPDATE photo_assets SET xmpFileUri = :xmpFileUri, xmpLastSyncedMtime = :mtime WHERE id = :id")
    suspend fun recordSidecar(id: String, xmpFileUri: String?, mtime: Long?)

    @Query(
        """
        UPDATE photo_assets SET rating = :rating, cullStatus = :cullStatus, colorLabel = :colorLabel, tags = :tagsJson
        WHERE id = :id AND xmpSyncStatus = 'WRITING'
        """
    )
    suspend fun applyFileState(id: String, rating: Int, cullStatus: CullStatus, colorLabel: ColorLabel, tagsJson: String)

    @Query(
        """
        UPDATE photo_assets SET xmpSyncStatus = 'SYNCED', xmpError = NULL, xmpPendingFields = 0
        WHERE id = :id AND xmpSyncStatus = 'WRITING'
        """
    )
    suspend fun markSynced(id: String)

    @Query(
        """
        UPDATE photo_assets SET xmpSyncStatus = :status, xmpError = :error
        WHERE id = :id AND xmpSyncStatus = 'WRITING'
        """
    )
    override suspend fun finishFailed(id: String, status: XmpSyncStatus, error: String?)

    @Query(
        """
        SELECT id FROM photo_assets
        WHERE xmpSyncStatus IN ('PENDING', 'WRITING')
            OR (xmpSyncStatus = 'ERROR' AND (xmpError IS NULL OR xmpError != :parseError))
        """
    )
    override suspend fun recoverableIds(parseError: String): List<String>

    @Query(
        """
        UPDATE photo_assets SET xmpSyncStatus = 'PENDING', xmpError = NULL, xmpPendingFields = xmpPendingFields | :fields
        WHERE id = :id
        """
    )
    override suspend fun markPending(id: String, fields: Int)

    @Query(
        """
        UPDATE photo_assets SET
            xmpFileUri = CASE WHEN xmpFileUri IN (:uris) THEN NULL ELSE xmpFileUri END,
            darktableXmpUri = CASE WHEN darktableXmpUri IN (:uris) THEN NULL ELSE darktableXmpUri END
        WHERE id = :id
        """
    )
    override suspend fun forgetSidecars(id: String, uris: List<String>)
}
