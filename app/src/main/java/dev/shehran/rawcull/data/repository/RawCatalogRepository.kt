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
import dev.shehran.rawcull.library.CullChange
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.util.SamplePhotosGenerator
import dev.shehran.rawcull.util.xmp.CullState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the Library needs from the catalog. */
interface PhotoCatalog {
    fun observeScope(rootUri: String, scope: LibraryScope): Flow<List<PhotoAsset>>

    fun observeFolderCounts(rootUri: String): Flow<Map<String, Int>>

    fun observeImportPhotoCount(jobId: Long): Flow<Int>

    suspend fun loadSampleShoot()

    /** Writes [change]'s `after` states as one batch; only fields that differ count as edited. */
    suspend fun applyChange(change: CullChange)

    /** The rows as they are now, which may be newer than what's on screen. */
    suspend fun photosById(ids: List<String>): List<PhotoAsset>

    /** Forgets rows whose files have gone. The import duplicate index keeps them on purpose (D9). */
    suspend fun removeRows(ids: List<String>)
}

class RawCatalogRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val dao: RawPhotoDao,
    private val writeQueue: XmpWriteQueue,
) : PhotoCatalog {
    private val converters = Converters()

    fun observeByRoot(rootUri: String): Flow<List<PhotoAsset>> = dao.observeByRoot(rootUri)

    /** One read, not an observer: for work over a whole Location, such as building previews. */
    suspend fun photosIn(rootUri: String): List<PhotoAsset> = withContext(Dispatchers.IO) { dao.getByRoot(rootUri) }

    /** A folder and everything under it, read once; an empty [folder] is the whole Location. */
    suspend fun photosInFolder(rootUri: String, folder: String): List<PhotoAsset> =
        withContext(Dispatchers.IO) { dao.getScope(rootUri, folder) }

    override fun observeScope(rootUri: String, scope: LibraryScope): Flow<List<PhotoAsset>> = when (scope) {
        LibraryScope.All -> dao.observeScope(rootUri, "")
        is LibraryScope.Folder -> dao.observeScope(rootUri, scope.relativeDir)
        is LibraryScope.LatestImport -> dao.observeByImportJob(scope.jobId)
        is LibraryScope.Collection -> database.collectionDao().observeMembers(scope.id)
    }

    override fun observeFolderCounts(rootUri: String): Flow<Map<String, Int>> = dao.observeFolderCounts(rootUri)

    override fun observeImportPhotoCount(jobId: Long): Flow<Int> = dao.observeImportPhotoCount(jobId)

    /** Generates the demo shoot once, so flags set on it survive reopening it. */
    override suspend fun loadSampleShoot() = withContext(Dispatchers.IO) {
        if (dao.getIdsByRoot(DEMO_ROOT_URI).isEmpty()) {
            dao.upsertAll(SamplePhotosGenerator.createSampleShoot(context))
        }
    }

    /**
     * Room commits first and the queue writes later, so nothing here waits for the card. Each field
     * is written only where it changed: marking the others pending would have the queue overwrite
     * what another app put in the sidecar.
     */
    override suspend fun applyChange(change: CullChange) = withContext(Dispatchers.IO) {
        database.withTransaction {
            changedBy(change) { it.cullStatus }.forEach { (status: CullStatus, ids) ->
                ids.chunked(MAX_IDS_PER_QUERY).forEach { dao.updateCullStatus(it, status) }
            }
            changedBy(change) { it.rating }.forEach { (rating: Int, ids) ->
                ids.chunked(MAX_IDS_PER_QUERY).forEach { dao.updateRating(it, rating.coerceIn(0, MAX_RATING)) }
            }
            changedBy(change) { it.colorLabel }.forEach { (label: ColorLabel, ids) ->
                ids.chunked(MAX_IDS_PER_QUERY).forEach { dao.updateColorLabel(it, label) }
            }
            changedBy(change) { it.tags }.forEach { (tags: List<String>, ids) ->
                val json = converters.fromTagsList(tags)
                ids.forEach { dao.updateTags(it, json) }
            }
        }
        writeQueue.enqueue(change.ids)
    }

    override suspend fun photosById(ids: List<String>): List<PhotoAsset> = withContext(Dispatchers.IO) {
        ids.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getByIds(it) }
    }

    override suspend fun removeRows(ids: List<String>) = withContext(Dispatchers.IO) {
        val collections = database.collectionDao()
        database.withTransaction {
            ids.chunked(MAX_IDS_PER_QUERY).forEach {
                dao.deleteByIds(it)
                collections.deleteItemsForAssets(it) // Deleted photos leave every collection (D3)
            }
        }
    }

    /** The ids whose [value] differs between before and after, grouped by the new value. */
    private fun <T> changedBy(change: CullChange, value: (CullState) -> T): Map<T, List<String>> =
        change.ids
            .filter { value(change.before.getValue(it)) != value(change.after.getValue(it)) }
            .groupBy { value(change.after.getValue(it)) }

    /** Debug builds only: rows that reuse the demo's files, for measuring large libraries (D12). */
    suspend fun addSyntheticPhotos(count: Int) = withContext(Dispatchers.IO) {
        loadSampleShoot()
        val samples = dao.getByRoot(DEMO_ROOT_URI).filterNot { it.id.startsWith(SYNTHETIC_PREFIX) }
        if (samples.isEmpty()) return@withContext
        val labels = ColorLabel.entries
        val statuses = CullStatus.entries
        (0 until count).chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
            dao.upsertAll(
                chunk.map { index ->
                    val sample = samples[index % samples.size]
                    sample.copy(
                        id = "$SYNTHETIC_PREFIX$index",
                        relativeDir = "Synthetic/2026-%02d".format(Locale.ROOT, index % MONTHS + 1),
                        fileName = "SYN_%05d.%s".format(Locale.ROOT, index, sample.format.extension),
                        dateTaken = SYNTHETIC_START + index * SYNTHETIC_STEP_MS,
                        rating = index % (MAX_RATING + 1),
                        cullStatus = statuses[index % statuses.size],
                        colorLabel = labels[index % labels.size],
                        tags = if (index % TAG_EVERY == 0) listOf("tag${index % TAG_KINDS}") else emptyList(),
                    )
                },
            )
        }
    }

    suspend fun removeSyntheticPhotos() = withContext(Dispatchers.IO) {
        dao.deleteByRootAndIdPrefix(DEMO_ROOT_URI, "$SYNTHETIC_PREFIX%")
    }

    private companion object {
        /** Older SQLite builds allow 999 bound variables per statement. */
        const val MAX_IDS_PER_QUERY = 500
        const val MAX_RATING = 5
        const val SYNTHETIC_PREFIX = "synthetic-"
        const val SYNTHETIC_START = 1_700_000_000_000L
        const val SYNTHETIC_STEP_MS = 60_000L
        const val MONTHS = 12
        const val TAG_EVERY = 4
        const val TAG_KINDS = 40
    }
}
