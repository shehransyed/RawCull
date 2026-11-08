package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.LibraryLocation

/** What part of the active Location the Library shows. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data class Folder(val relativeDir: String) : LibraryScope
    data class LatestImport(val jobId: Long) : LibraryScope
    data class Collection(val id: Long) : LibraryScope

    /** The value saved between launches. *Latest Import* isn't one: it is a view, not a place. */
    val savedFolder: String
        get() = (this as? Folder)?.relativeDir.orEmpty()

    /** The collection to reopen on, or null (a collection is a place of its own, not a folder). */
    val savedCollectionId: Long?
        get() = (this as? Collection)?.id

    companion object {
        fun fromSavedFolder(folder: String): LibraryScope = if (folder.isEmpty()) All else Folder(folder)

        /** A saved collection wins over a saved folder: they are never saved together. */
        fun fromSaved(folder: String, collectionId: Long?): LibraryScope =
            collectionId?.let(::Collection) ?: fromSavedFolder(folder)
    }
}

/** Builds the `LIKE` prefix that matches everything under a folder, and nothing that merely resembles it. */
object FolderQuery {
    const val ESCAPE = '\\'

    fun descendantsPattern(folder: String): String = buildString {
        for (char in folder) {
            if (char == '%' || char == '_' || char == ESCAPE) append(ESCAPE)
            append(char)
        }
        append("/%")
    }
}

/** What the last session was looking at, as far as it still exists. */
data class RestoredSession(
    val location: LibraryLocation?,
    val scope: LibraryScope,
    val selectedAssetId: String?,
)

object LibraryRestore {

    /**
     * @param savedLocationId falls back to the most recently opened Location if it's gone.
     * @param savedFolder and [savedAssetId] only apply to the Location they were saved for.
     */
    fun resolve(
        locations: List<LibraryLocation>,
        savedLocationId: Long?,
        savedFolder: String,
        savedAssetId: String?,
        savedCollectionId: Long? = null,
    ): RestoredSession {
        val saved = locations.firstOrNull { it.id == savedLocationId }
        if (saved != null) return RestoredSession(saved, LibraryScope.fromSaved(savedFolder, savedCollectionId), savedAssetId)
        return RestoredSession(locations.maxByOrNull { it.lastOpenedAt }, LibraryScope.All, null)
    }
}
