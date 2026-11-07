package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportJobStatus
import kotlinx.coroutines.flow.Flow

private const val ITEM_CHUNK = 500

@Dao
interface ImportJobDao {
    @Insert
    suspend fun insertJob(job: ImportJob): Long

    @Insert
    suspend fun insertItems(items: List<ImportJobItem>)

    /** The job and its items become visible together, so a worker never sees half a plan. */
    @Transaction
    suspend fun insertJobWithItems(job: ImportJob, items: List<ImportJobItem>): Long {
        val jobId = insertJob(job)
        items.chunked(ITEM_CHUNK).forEach { chunk -> insertItems(chunk.map { it.copy(jobId = jobId) }) }
        return jobId
    }

    @Update
    suspend fun updateJob(job: ImportJob)

    @Update
    suspend fun updateItem(item: ImportJobItem)

    @Query("SELECT * FROM import_jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: Long): ImportJob?

    @Query("SELECT * FROM import_jobs WHERE id = :id LIMIT 1")
    fun observeJob(id: Long): Flow<ImportJob?>

    @Query("SELECT * FROM import_job_items WHERE jobId = :jobId ORDER BY captureDate ASC, id ASC")
    suspend fun getItems(jobId: Long): List<ImportJobItem>

    @Query("SELECT * FROM import_job_items WHERE jobId = :jobId ORDER BY captureDate ASC, id ASC")
    fun observeItems(jobId: Long): Flow<List<ImportJobItem>>

    @Query("SELECT * FROM import_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY id DESC LIMIT 1")
    fun observeActiveJob(): Flow<ImportJob?>

    @Query("SELECT * FROM import_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY id DESC LIMIT 1")
    suspend fun getActiveJob(): ImportJob?

    /** The newest job that brought something into [locationId]: what *Latest Import* shows. */
    @Query("SELECT * FROM import_jobs WHERE locationId = :locationId AND doneFiles > 0 ORDER BY id DESC LIMIT 1")
    fun observeLatestJob(locationId: Long): Flow<ImportJob?>

    @Query("UPDATE import_jobs SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ImportJobStatus)

    @Query("UPDATE import_job_items SET state = 'QUEUED', error = NULL WHERE jobId = :jobId AND state = 'FAILED'")
    suspend fun requeueFailed(jobId: Long)
}
