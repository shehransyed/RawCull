package dev.shehran.rawcull.data.preview

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One thumbnail file. [path] is relative to the store's root. */
@Entity(tableName = "thumbnails", indices = [Index("lastAccess")])
data class ThumbnailRow(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    val path: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val lastAccess: Long,
)

/** What a file holds, so the next open of it is a single positional read. */
@Entity(tableName = "scans")
data class ScanRow(
    @PrimaryKey
    val fileKey: String,
    val candidatesJson: String,
    val orientation: Int,
    val scannedAt: Long,
)

@Dao
interface PreviewCacheDao {
    @Query("SELECT * FROM thumbnails WHERE `key` = :key")
    suspend fun thumbnail(key: String): ThumbnailRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putThumbnail(row: ThumbnailRow)

    @Query("UPDATE thumbnails SET lastAccess = :at WHERE `key` = :key")
    suspend fun touch(key: String, at: Long)

    @Query("SELECT * FROM thumbnails ORDER BY lastAccess ASC, `key` ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<ThumbnailRow>

    @Query("SELECT * FROM thumbnails")
    suspend fun allThumbnails(): List<ThumbnailRow>

    @Query("SELECT `key` FROM thumbnails WHERE `key` IN (:keys)")
    suspend fun existing(keys: List<String>): List<String>

    @Query("DELETE FROM thumbnails WHERE `key` IN (:keys)")
    suspend fun deleteThumbnails(keys: List<String>)

    @Query("DELETE FROM scans WHERE fileKey IN (:keys)")
    suspend fun deleteScans(keys: List<String>)

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM thumbnails")
    suspend fun usedBytes(): Long

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM thumbnails")
    fun observeUsedBytes(): Flow<Long>

    @Query("DELETE FROM thumbnails")
    suspend fun clearThumbnails()

    @Query("DELETE FROM scans")
    suspend fun clearScans()

    @Query("SELECT * FROM scans WHERE fileKey = :fileKey")
    suspend fun scan(fileKey: String): ScanRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScan(row: ScanRow)
}

/** Separate from the catalog on purpose: everything in here can be rebuilt from the photos. */
@Database(entities = [ThumbnailRow::class, ScanRow::class], version = 1, exportSchema = true)
abstract class PreviewCacheDatabase : RoomDatabase() {
    abstract fun dao(): PreviewCacheDao

    companion object {
        /** The one database allowed to lose its contents on an upgrade (D6): it's a cache. */
        fun build(context: Context): PreviewCacheDatabase = Room.databaseBuilder(
            context.applicationContext,
            PreviewCacheDatabase::class.java,
            "preview_cache.db",
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}
