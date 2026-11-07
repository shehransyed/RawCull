package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.room.withTransaction
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.Converters
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.util.SamplePhotosGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RawCatalogRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val dao: RawPhotoDao,
    private val writeQueue: XmpWriteQueue,
) {
    private val converters = Converters()

    fun observeByRoot(rootUri: String): Flow<List<PhotoAsset>> = dao.observeByRoot(rootUri)

    fun observeScope(rootUri: String, scope: LibraryScope): Flow<List<PhotoAsset>> = when (scope) {
        LibraryScope.All -> dao.observeScope(rootUri, "")
        is LibraryScope.Folder -> dao.observeScope(rootUri, scope.relativeDir)
        is LibraryScope.LatestImport -> dao.observeByImportJob(scope.jobId)
    }

    fun observeFolderCounts(rootUri: String): Flow<Map<String, Int>> = dao.observeFolderCounts(rootUri)

    fun observeImportPhotoCount(jobId: Long): Flow<Int> = dao.observeImportPhotoCount(jobId)

    /** Generates the demo shoot once, so flags set on it survive reopening it. */
    suspend fun loadSampleShoot() = withContext(Dispatchers.IO) {
        if (dao.getIdsByRoot(DEMO_ROOT_URI).isEmpty()) {
            dao.upsertAll(SamplePhotosGenerator.createSampleShoot(context))
        }
    }

    /** Room commits first and the queue writes later, so nothing here waits for the card. */
    private suspend fun updateAll(ids: List<String>, update: suspend (List<String>) -> Unit) {
        database.withTransaction { ids.chunked(MAX_IDS_PER_QUERY).forEach { update(it) } }
        writeQueue.enqueue(ids)
    }

    suspend fun setCullStatus(id: String, status: CullStatus) = batchSetCullStatus(listOf(id), status)

    suspend fun setRating(id: String, rating: Int) = batchSetRating(listOf(id), rating)

    suspend fun setColorLabel(id: String, label: ColorLabel) = batchSetColorLabel(listOf(id), label)

    suspend fun batchSetCullStatus(ids: List<String>, status: CullStatus) = withContext(Dispatchers.IO) {
        updateAll(ids) { dao.updateCullStatus(it, status) }
    }

    suspend fun batchSetRating(ids: List<String>, rating: Int) = withContext(Dispatchers.IO) {
        updateAll(ids) { dao.updateRating(it, rating.coerceIn(0, 5)) }
    }

    suspend fun batchSetColorLabel(ids: List<String>, label: ColorLabel) = withContext(Dispatchers.IO) {
        updateAll(ids) { dao.updateColorLabel(it, label) }
    }

    suspend fun batchAddTag(ids: List<String>, tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        editTags(ids) { tags -> if (trimmed in tags) tags else tags + trimmed }
    }

    suspend fun removeTag(id: String, tag: String) = editTags(listOf(id)) { it - tag }

    private suspend fun editTags(ids: List<String>, edit: (List<String>) -> List<String>) = withContext(Dispatchers.IO) {
        val changed = database.withTransaction {
            ids.filter { id ->
                val tags = dao.getPhotoById(id)?.tags ?: return@filter false
                val edited = edit(tags)
                if (edited != tags) dao.updateTags(id, converters.fromTagsList(edited))
                edited != tags
            }
        }
        writeQueue.enqueue(changed)
    }

    private companion object {
        /** Older SQLite builds allow 999 bound variables per statement. */
        const val MAX_IDS_PER_QUERY = 500
    }
}
