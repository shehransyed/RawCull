package dev.shehran.rawcull.data.repository

import androidx.room.withTransaction
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.CollectionItem
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.library.CollectionOrder
import dev.shehran.rawcull.library.CollectionTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** A collection change, or why it was refused. */
sealed interface CollectionChange {
    data class Done(val id: Long) : CollectionChange
    data class Refused(val message: String) : CollectionChange
}

/**
 * Collections and collection sets (D2, D3). They live in the catalog only (D4); a photo's files
 * and sidecars never change here. Deleting a collection never touches its photos.
 */
class CollectionRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val dao = database.collectionDao()

    fun observeTree(locationKey: Long): Flow<List<PhotoCollection>> = dao.observeForLocation(locationKey)

    suspend fun tree(locationKey: Long): CollectionTree = CollectionTree(dao.getForLocation(locationKey))

    suspend fun get(id: Long): PhotoCollection? = dao.getById(id)

    suspend fun createCollection(locationKey: Long, name: String, parentId: Long? = null): CollectionChange =
        create(locationKey, name, parentId, isSet = false)

    suspend fun createSet(locationKey: Long, name: String, parentId: Long? = null): CollectionChange =
        create(locationKey, name, parentId, isSet = true)

    private suspend fun create(locationKey: Long, name: String, parentId: Long?, isSet: Boolean): CollectionChange =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val clean = name.trim()
                if (clean.isEmpty()) return@withTransaction CollectionChange.Refused("Give it a name.")
                val tree = tree(locationKey)
                if (parentId != null && tree.byId[parentId]?.isSet != true) {
                    return@withTransaction CollectionChange.Refused("Only a collection set can hold collections.")
                }
                if (tree.nameTaken(parentId, clean)) return@withTransaction taken(clean)
                val row = PhotoCollection(
                    locationId = locationKey,
                    parentId = parentId,
                    name = clean,
                    isSet = isSet,
                    createdAt = clock(),
                )
                CollectionChange.Done(dao.insert(row))
            }
        }

    suspend fun rename(id: Long, name: String): CollectionChange = withContext(Dispatchers.IO) {
        database.withTransaction {
            val clean = name.trim()
            if (clean.isEmpty()) return@withTransaction CollectionChange.Refused("Give it a name.")
            val row = dao.getById(id) ?: return@withTransaction gone()
            val tree = tree(row.locationId)
            if (tree.nameTaken(tree.effectiveParent(row), clean, exceptId = id)) return@withTransaction taken(clean)
            dao.rename(id, clean)
            CollectionChange.Done(id)
        }
    }

    /** Moves [id] into the set [newParentId], or to the top level when null. Never makes a cycle. */
    suspend fun move(id: Long, newParentId: Long?): CollectionChange = withContext(Dispatchers.IO) {
        database.withTransaction {
            val row = dao.getById(id) ?: return@withTransaction gone()
            tree(row.locationId).moveRefusal(id, newParentId)?.let {
                return@withTransaction CollectionChange.Refused(it)
            }
            dao.move(id, newParentId)
            CollectionChange.Done(id)
        }
    }

    suspend fun setSortOrder(id: Long, sortOrder: String?) = withContext(Dispatchers.IO) {
        dao.setSortOrder(id, sortOrder)
    }

    /** Deletes [id] and, for a set, everything inside it. Photos are never touched. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val row = dao.getById(id) ?: return@withTransaction
            // The foreign keys cascade too; deleting explicitly doesn't depend on the pragma.
            val ids = tree(row.locationId).subtree(id).toList()
            ids.chunked(CHUNK).forEach {
                dao.deleteItemsOf(it)
                dao.deleteCollections(it)
            }
        }
    }

    /** Removing a Location removes its collections (D2). */
    suspend fun deleteForLocation(locationKey: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val ids = dao.getForLocation(locationKey).map { it.id }
            ids.chunked(CHUNK).forEach { dao.deleteItemsOf(it) }
            dao.deleteForLocation(locationKey)
        }
    }

    /**
     * Appends [assetIds] that aren't members yet, in the given order (D7: new photos go to the end).
     * Returns the ids actually added, for Undo. Sets can't hold photos.
     */
    suspend fun add(collectionId: Long, assetIds: List<String>): List<String> = withContext(Dispatchers.IO) {
        database.withTransaction {
            val collection = dao.getById(collectionId)
            if (collection == null || collection.isSet) return@withTransaction emptyList()
            val ids = assetIds.distinct()
            val existing = ids.chunked(CHUNK).flatMap { dao.itemsFor(collectionId, it) }.map { it.assetId }.toSet()
            val fresh = ids.filterNot { it in existing }
            var position = (dao.maxPosition(collectionId) ?: -1) + 1
            val now = clock()
            fresh.chunked(CHUNK).forEach { chunk ->
                dao.insertItems(chunk.map { CollectionItem(collectionId, it, position++, now) })
            }
            fresh
        }
    }

    /** Removes [assetIds] and returns the items as they were, so [restore] can undo it. */
    suspend fun remove(collectionId: Long, assetIds: List<String>): List<CollectionItem> =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                assetIds.distinct().chunked(CHUNK).flatMap { chunk ->
                    dao.itemsFor(collectionId, chunk).also { dao.removeItems(collectionId, chunk) }
                }
            }
        }

    /** Puts removed items back in their old places; ones already back are left alone. */
    suspend fun restore(items: List<CollectionItem>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val live = items.filter { dao.getById(it.collectionId)?.isSet == false }
            live.chunked(CHUNK).forEach { dao.insertItems(it) }
        }
    }

    /**
     * Rewrites the whole collection's order in one transaction (D7). Every member stays exactly
     * once; see [CollectionOrder.normalized].
     */
    suspend fun reorder(collectionId: Long, orderedIds: List<String>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val order = CollectionOrder.normalized(dao.memberIds(collectionId), orderedIds)
            order.forEachIndexed { index, id -> dao.setPosition(collectionId, id, index) }
        }
    }

    /** Every member id in custom order, including ids that have no row right now. */
    suspend fun memberIds(collectionId: Long): List<String> = withContext(Dispatchers.IO) {
        dao.memberIds(collectionId)
    }

    fun observeMemberIds(collectionId: Long): Flow<List<String>> = dao.observeMemberIds(collectionId)

    /** The members that have a row, in custom order. */
    suspend fun members(collectionId: Long): List<PhotoAsset> = withContext(Dispatchers.IO) {
        dao.members(collectionId)
    }

    /** [members] as a live query: rows appear and vanish with the catalog, ids without a row are skipped. */
    fun observeMembers(collectionId: Long): Flow<List<PhotoAsset>> = dao.observeMembers(collectionId)

    fun observeContaining(assetId: String): Flow<List<PhotoCollection>> = dao.observeContaining(assetId)

    suspend fun containing(assetId: String): List<PhotoCollection> = withContext(Dispatchers.IO) {
        dao.containing(assetId)
    }

    private fun taken(name: String) = CollectionChange.Refused("There's already a \"$name\" there.")

    private fun gone() = CollectionChange.Refused("That collection no longer exists.")

    private companion object {
        const val CHUNK = 500 // Older SQLite builds allow 999 bound variables per statement
    }
}
