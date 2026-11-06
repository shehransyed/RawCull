package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPhotoDao {
    @Query("SELECT * FROM photo_assets WHERE rootUri = :rootUri ORDER BY dateTaken ASC")
    fun getPhotosForFolder(rootUri: String): Flow<List<PhotoAsset>>

    @Query("SELECT * FROM photo_assets WHERE rootUri = :rootUri ORDER BY dateTaken ASC")
    suspend fun getPhotosForFolderSync(rootUri: String): List<PhotoAsset>

    @Query("SELECT * FROM photo_assets ORDER BY dateTaken ASC")
    fun getAllPhotos(): Flow<List<PhotoAsset>>

    @Query("SELECT * FROM photo_assets WHERE id = :id LIMIT 1")
    suspend fun getPhotoById(id: String): PhotoAsset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<PhotoAsset>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: PhotoAsset)

    @Update
    suspend fun updatePhoto(photo: PhotoAsset)

    @Query("UPDATE photo_assets SET cullStatus = :status, lastModified = :time WHERE id = :id")
    suspend fun updateCullStatus(id: String, status: CullStatus, time: Long = System.currentTimeMillis())

    @Query("UPDATE photo_assets SET rating = :rating, lastModified = :time WHERE id = :id")
    suspend fun updateRating(id: String, rating: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE photo_assets SET colorLabel = :label, lastModified = :time WHERE id = :id")
    suspend fun updateColorLabel(id: String, label: ColorLabel, time: Long = System.currentTimeMillis())

    @Query("UPDATE photo_assets SET xmpFileUri = :xmpFileUri, xmpSyncStatus = :status WHERE id = :id")
    suspend fun updateXmpState(id: String, xmpFileUri: String?, status: XmpSyncStatus)

    @Query("UPDATE photo_assets SET cachedThumbnailPath = :thumbnailPath WHERE id = :id")
    suspend fun updateCachedThumbnailPath(id: String, thumbnailPath: String)

    @Query("DELETE FROM photo_assets WHERE rootUri = :rootUri")
    suspend fun deletePhotosForFolder(rootUri: String)

    @Query("DELETE FROM photo_assets")
    suspend fun clearAll()
}
