package dev.shehran.rawcull.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.shehran.rawcull.data.model.CollectionItem
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoCollection
import kotlinx.coroutines.flow.Flow

/** Id lists passed to `IN (...)` are chunked by the callers; see CollectionRepository. */
@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections WHERE locationId = :locationKey")
    fun observeForLocation(locationKey: Long): Flow<List<PhotoCollection>>

    @Query("SELECT * FROM collections WHERE locationId = :locationKey")
    suspend fun getForLocation(locationKey: Long): List<PhotoCollection>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PhotoCollection?

    @Insert
    suspend fun insert(collection: PhotoCollection): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE collections SET parentId = :parentId WHERE id = :id")
    suspend fun move(id: Long, parentId: Long?)

    @Query("UPDATE collections SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: String?)

    @Query("DELETE FROM collections WHERE id IN (:ids)")
    suspend fun deleteCollections(ids: List<Long>)

    @Query("DELETE FROM collections WHERE locationId = :locationKey")
    suspend fun deleteForLocation(locationKey: Long)

    /** Returns -1 for each item that was already a member. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<CollectionItem>): List<Long>

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId AND assetId IN (:assetIds)")
    suspend fun itemsFor(collectionId: Long, assetIds: List<String>): List<CollectionItem>

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND assetId IN (:assetIds)")
    suspend fun removeItems(collectionId: Long, assetIds: List<String>)

    @Query("DELETE FROM collection_items WHERE collectionId IN (:collectionIds)")
    suspend fun deleteItemsOf(collectionIds: List<Long>)

    /** Photos deleted from the library leave every collection. */
    @Query("DELETE FROM collection_items WHERE assetId IN (:assetIds)")
    suspend fun deleteItemsForAssets(assetIds: List<String>)

    @Query("SELECT MAX(position) FROM collection_items WHERE collectionId = :collectionId")
    suspend fun maxPosition(collectionId: Long): Int?

    @Query("UPDATE collection_items SET position = :position WHERE collectionId = :collectionId AND assetId = :assetId")
    suspend fun setPosition(collectionId: Long, assetId: String, position: Int)

    /** Every member id, rows or not, in the collection's custom order. */
    @Query("SELECT assetId FROM collection_items WHERE collectionId = :collectionId ORDER BY position, addedAt, assetId")
    suspend fun memberIds(collectionId: Long): List<String>

    @Query("SELECT assetId FROM collection_items WHERE collectionId = :collectionId ORDER BY position, addedAt, assetId")
    fun observeMemberIds(collectionId: Long): Flow<List<String>>

    /** The members that have a row, in custom order; ids without one are skipped (D3). */
    @Query(
        "SELECT p.* FROM photo_assets p JOIN collection_items i ON i.assetId = p.id " +
            "WHERE i.collectionId = :collectionId ORDER BY i.position, i.addedAt, i.assetId",
    )
    suspend fun members(collectionId: Long): List<PhotoAsset>

    @Query(
        "SELECT c.* FROM collections c JOIN collection_items i ON i.collectionId = c.id " +
            "WHERE i.assetId = :assetId ORDER BY c.name COLLATE NOCASE",
    )
    fun observeContaining(assetId: String): Flow<List<PhotoCollection>>

    @Query(
        "SELECT c.* FROM collections c JOIN collection_items i ON i.collectionId = c.id " +
            "WHERE i.assetId = :assetId ORDER BY c.name COLLATE NOCASE",
    )
    suspend fun containing(assetId: String): List<PhotoCollection>
}
