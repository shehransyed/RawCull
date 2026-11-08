package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoCollection
import java.util.Locale

/** One line in the *Add to collection* sheet: a collection and where it sits ("Trips / Iceland"). */
data class CollectionChoice(val collection: PhotoCollection, val path: String)

/** What the sheet lists (D6): collections only, the target first, then the newest, narrowed by a search. */
object CollectionPicker {
    fun choices(all: List<PhotoCollection>, query: String, targetId: Long?): List<CollectionChoice> {
        val tree = CollectionTree(all)
        val needle = query.trim().lowercase(Locale.ROOT)
        return all.asSequence()
            .filter { !it.isSet }
            .map { CollectionChoice(it, (tree.ancestors(it.id).map(PhotoCollection::name) + it.name).joinToString(" / ")) }
            .filter { needle.isEmpty() || it.path.lowercase(Locale.ROOT).contains(needle) }
            .sortedWith(
                compareByDescending<CollectionChoice> { it.collection.id == targetId }
                    .thenByDescending { it.collection.createdAt }
                    .thenByDescending { it.collection.id },
            )
            .toList()
    }

    /** Whether typing [query] should offer "New collection": it has text and no top-level sibling already has that name. */
    fun canCreate(all: List<PhotoCollection>, query: String): Boolean =
        query.isNotBlank() && !CollectionTree(all).nameTaken(null, query)
}
