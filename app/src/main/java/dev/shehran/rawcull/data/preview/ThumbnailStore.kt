package dev.shehran.rawcull.data.preview

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A thumbnail as stored: upright JPEG bytes. */
class StoredThumbnail(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Bounded, persistent thumbnails: one JPEG per key, under [root], indexed in `preview_cache.db`.
 * The system may empty `cacheDir` at any time, so a file missing behind the index is a normal
 * cache miss rather than an error.
 */
class ThumbnailStore(
    private val dao: PreviewCacheDao,
    private val root: File,
    private val limitBytes: suspend () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val writesSinceEviction = AtomicInteger()
    private val renameLock = Any()

    fun observeUsedBytes(): Flow<Long> = dao.observeUsedBytes()

    suspend fun usedBytes(): Long = dao.usedBytes()

    suspend fun contains(key: String): Boolean = withContext(io) {
        dao.thumbnail(key)?.let { File(root, it.path).exists() } ?: false
    }

    suspend fun read(key: String): StoredThumbnail? = withContext(io) {
        val row = dao.thumbnail(key) ?: return@withContext null
        val file = File(root, row.path)
        val jpeg = try {
            file.readBytes()
        } catch (e: IOException) {
            dao.deleteThumbnails(listOf(key)) // Gone behind the index
            return@withContext null
        }
        val now = clock()
        if (now - row.lastAccess >= TOUCH_INTERVAL_MS) dao.touch(key, now)
        StoredThumbnail(jpeg, row.width, row.height)
    }

    /** A crash can't leave half a JPEG: the name only ever points at a complete file. */
    suspend fun write(key: String, jpeg: ByteArray, width: Int, height: Int) {
        withContext(io) {
            val path = "${key.take(2)}/$key.jpg"
            val target = File(root, path)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$key.${UUID.randomUUID()}$TEMP_SUFFIX")
            try {
                temp.writeBytes(jpeg)
                // A key is the file's identity, so another writer's finished copy is just as good.
                val placed = synchronized(renameLock) { temp.renameTo(target) || target.exists() }
                if (!placed) throw IOException("Couldn't move ${temp.name} into place")
            } finally {
                temp.delete()
            }
            dao.putThumbnail(ThumbnailRow(key, path, jpeg.size.toLong(), width, height, clock()))
        }
        if (writesSinceEviction.incrementAndGet() >= EVICTION_INTERVAL) evict()
    }

    /** Oldest first, in batches, until the store fits its limit. */
    suspend fun evict() = withContext(io) {
        writesSinceEviction.set(0)
        val limit = limitBytes()
        var used = dao.usedBytes()
        while (used > limit) {
            val batch = dao.oldest(EVICTION_BATCH)
            if (batch.isEmpty()) break
            var freed = 0L
            val keys = ArrayList<String>(batch.size)
            for (row in batch) {
                if (used - freed <= limit) break
                File(root, row.path).delete()
                keys += row.key
                freed += row.bytes
            }
            dao.deleteThumbnails(keys)
            dao.deleteScans(keys)
            used -= freed
        }
    }

    /** These thumbnails and their scans, files included. */
    suspend fun remove(keys: Collection<String>) = withContext(io) {
        keys.chunked(SQL_BATCH).forEach { batch ->
            batch.forEach { key -> dao.thumbnail(key)?.let { File(root, it.path).delete() } }
            dao.deleteThumbnails(batch)
            dao.deleteScans(batch)
        }
    }

    suspend fun clear() = withContext(io) {
        dao.clearThumbnails()
        dao.clearScans()
        root.deleteRecursively()
        Unit
    }

    /** Brings index and folder back together after the system has been at `cacheDir`. */
    suspend fun reconcile() = withContext(io) {
        val rows = dao.allThumbnails()
        val missing = rows.filterNot { File(root, it.path).exists() }.map { it.key }
        missing.chunked(SQL_BATCH).forEach { dao.deleteThumbnails(it) }

        val known = rows.mapTo(HashSet()) { File(root, it.path).absolutePath }
        root.walkTopDown().filter { it.isFile && it.absolutePath !in known }.forEach { it.delete() }
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
        const val TOUCH_INTERVAL_MS = 60_000L
        const val EVICTION_INTERVAL = 16
        const val EVICTION_BATCH = 200
        const val SQL_BATCH = 500
    }
}
