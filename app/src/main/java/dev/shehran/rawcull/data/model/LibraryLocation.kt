package dev.shehran.rawcull.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named library root: a persisted SAF tree, or [DEMO_ROOT_URI] for the demo library. */
@Entity(tableName = "library_locations", indices = [Index(value = ["treeUri"], unique = true)])
data class LibraryLocation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
) {
    val isDemo: Boolean get() = treeUri == DEMO_ROOT_URI
}
