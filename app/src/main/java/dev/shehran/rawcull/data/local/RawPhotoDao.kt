package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.RawPhotoItem
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPhotoDao {
    @Query("SELECT * FROM raw_photos WHERE folderUri = :folderUri ORDER BY dateTaken ASC")
    fun getPhotosForFolder(folderUri: String): Flow<List<RawPhotoItem>>

    @Query("SELECT * FROM raw_photos WHERE folderUri = :folderUri ORDER BY dateTaken ASC")
    suspend fun getPhotosForFolderSync(folderUri: String): List<RawPhotoItem>

    @Query("SELECT * FROM raw_photos ORDER BY dateTaken ASC")
    fun getAllPhotos(): Flow<List<RawPhotoItem>>

    @Query("SELECT * FROM raw_photos WHERE id = :id LIMIT 1")
    suspend fun getPhotoById(id: String): RawPhotoItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<RawPhotoItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: RawPhotoItem)

    @Update
    suspend fun updatePhoto(photo: RawPhotoItem)

    @Query("UPDATE raw_photos SET cullStatus = :status, lastModified = :time WHERE id = :id")
    suspend fun updateCullStatus(id: String, status: CullStatus, time: Long = System.currentTimeMillis())

    @Query("UPDATE raw_photos SET rating = :rating, lastModified = :time WHERE id = :id")
    suspend fun updateRating(id: String, rating: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE raw_photos SET colorLabel = :label, lastModified = :time WHERE id = :id")
    suspend fun updateColorLabel(id: String, label: ColorLabel, time: Long = System.currentTimeMillis())

    @Query("UPDATE raw_photos SET cachedThumbnailPath = :thumbnailPath WHERE id = :id")
    suspend fun updateCachedThumbnailPath(id: String, thumbnailPath: String)

    @Query("DELETE FROM raw_photos WHERE folderUri = :folderUri")
    suspend fun deletePhotosForFolder(folderUri: String)

    @Query("DELETE FROM raw_photos WHERE isSample = 1")
    suspend fun deleteSamplePhotos()

    @Query("DELETE FROM raw_photos")
    suspend fun clearAll()
}
