package dev.shehran.rawcull.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A collection (holds photos) or a collection set (holds collections and sets, never photos).
 * [locationId] is the Location's id, `0` for the demo library, so there is no foreign key to it.
 * [parentId] is the set it sits in, null at the top level; deleting a set deletes what's inside.
 */
@Entity(
    tableName = "collections",
    foreignKeys = [
        ForeignKey(
            entity = PhotoCollection::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("locationId"), Index("parentId")],
)
data class PhotoCollection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val locationId: Long,
    val parentId: Long? = null,
    val name: String,
    val isSet: Boolean = false,
    val sortOrder: String? = null, // A SortOrder name; null is the collection's default (custom order)
    val createdAt: Long,
) {
    companion object {
        /** Collections of the demo library use this instead of its Location row's id (D2). */
        const val DEMO_LOCATION_KEY = 0L

        fun locationKey(location: LibraryLocation): Long =
            if (location.isDemo) DEMO_LOCATION_KEY else location.id
    }
}

/**
 * A photo's place in a collection. There is no foreign key to `photo_assets`: a photo that is
 * missing after a rescan, or on an unavailable Location, keeps its place, and views skip it (D3).
 */
@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assetId"), Index(value = ["collectionId", "position"])],
)
data class CollectionItem(
    val collectionId: Long,
    val assetId: String,
    val position: Int,
    val addedAt: Long,
)
