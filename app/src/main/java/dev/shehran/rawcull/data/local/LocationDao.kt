package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.shehran.rawcull.data.model.LibraryLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM library_locations ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LibraryLocation>>

    @Query("SELECT * FROM library_locations ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<LibraryLocation>

    @Query("SELECT * FROM library_locations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LibraryLocation?

    @Query("SELECT * FROM library_locations WHERE treeUri = :treeUri LIMIT 1")
    suspend fun getByTreeUri(treeUri: String): LibraryLocation?

    /** Returns the new id, or -1 if the tree is already a Location. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(location: LibraryLocation): Long

    @Query("UPDATE library_locations SET displayName = :displayName WHERE id = :id")
    suspend fun rename(id: Long, displayName: String)

    @Query("UPDATE library_locations SET treeUri = :treeUri WHERE id = :id")
    suspend fun updateTreeUri(id: Long, treeUri: String)

    @Query("UPDATE library_locations SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Delete
    suspend fun delete(location: LibraryLocation)
}
