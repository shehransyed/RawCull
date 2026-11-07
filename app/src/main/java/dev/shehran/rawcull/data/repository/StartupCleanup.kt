package dev.shehran.rawcull.data.repository

import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import java.net.URLDecoder

/** Folders remembered from before Locations existed. */
data class LegacyFolders(val destinationUri: String?, val sourceUri: String?)

/** The decisions of [StartupCleanup], free of Android so they can be tested directly. */
object LegacySources {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary:"

    /** A tree on a card or USB drive: a source for imports, never a library of its own. */
    fun isRemovableVolume(treeUri: String): Boolean {
        if (EXTERNAL_STORAGE !in treeUri) return false
        val documentId = URLDecoder.decode(treeUri.substringAfter("/tree/"), Charsets.UTF_8.name())
        return !documentId.startsWith(PRIMARY_VOLUME)
    }

    /** What existing users should find as Locations, so they land in their library. */
    fun locationsToCreate(legacy: LegacyFolders, persisted: Set<String>): List<String> = buildList {
        legacy.destinationUri?.takeIf { it in persisted }?.let(::add)
        legacy.sourceUri?.takeIf { it in persisted && !isRemovableVolume(it) }?.let(::add)
    }.distinct()

    fun grantsToRelease(persisted: Set<String>, inUse: Set<String>): Set<String> = persisted - inUse
}

/**
 * Runs once per process, before the Library restores itself: turns folders from older versions
 * into Locations, then lets go of grants nothing needs any more.
 *
 * @param unfinishedJobSources sources a job may still have to read when it resumes.
 * @param legacyPreviewCache the folder the old preview loader filled without any limit.
 */
class StartupCleanup(
    private val legacyFolders: suspend () -> LegacyFolders?,
    private val clearLegacyFolders: suspend () -> Unit,
    private val locations: LocationRepository,
    private val volumeGrants: suspend () -> Collection<String>,
    private val unfinishedJobSources: suspend () -> List<String>,
    private val persistedGrants: () -> Set<String>,
    private val release: (String) -> Unit,
    private val removeOrphanRows: suspend (keepRoots: List<String>) -> Unit,
    private val legacyPreviewCache: LegacyPreviewCache,
) {
    suspend fun run() {
        val persisted = persistedGrants()
        legacyFolders()?.let { legacy ->
            LegacySources.locationsToCreate(legacy, persisted).forEach { locations.add(it) }
            clearLegacyFolders()
        }

        val inUse = locations.getAll().map { it.treeUri }.toSet() + volumeGrants() + unfinishedJobSources()
        LegacySources.grantsToRelease(persisted, inUse).forEach(release)
        removeOrphanRows(locations.getAll().map { it.treeUri } + DEMO_ROOT_URI)
        legacyPreviewCache.clearOnce()
    }
}

/** Deleted once, on the first start of 0.7.0. It's a cache, so nothing the user sees is lost. */
class LegacyPreviewCache(
    private val isCleared: suspend () -> Boolean,
    private val markCleared: suspend () -> Unit,
    private val delete: () -> Unit,
) {
    suspend fun clearOnce() {
        if (isCleared()) return
        delete()
        markCleared()
    }
}
