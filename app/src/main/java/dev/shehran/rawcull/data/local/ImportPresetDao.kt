package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.shehran.rawcull.data.model.ImportPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportPresetDao {
    @Query("SELECT * FROM import_presets ORDER BY id ASC")
    fun observeAll(): Flow<List<ImportPreset>>

    @Query("SELECT * FROM import_presets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImportPreset?

    @Insert
    suspend fun insert(preset: ImportPreset): Long

    @Update
    suspend fun update(preset: ImportPreset)

    @Delete
    suspend fun delete(preset: ImportPreset)
}
