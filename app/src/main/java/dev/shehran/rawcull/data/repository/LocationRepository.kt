package dev.shehran.rawcull.data.repository

import android.content.Context
import android.net.Uri
import dev.shehran.rawcull.data.local.LocationDao
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.util.SafPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The storage side of a Location, kept apart so the rules below can be tested without Android. */
interface LocationAccess {
    fun takePersistable(treeUri: String): Boolean
    fun release(treeUri: String)
    fun isAvailable(treeUri: String): Boolean
    fun displayName(treeUri: String): String
}

class SafLocationAccess(context: Context) : LocationAccess {
    private val appContext = context.applicationContext

    override fun takePersistable(treeUri: String) = SafPermissions.takePersistable(appContext, Uri.parse(treeUri))
    override fun release(treeUri: String) = SafPermissions.release(appContext, Uri.parse(treeUri))
    override fun isAvailable(treeUri: String) = SafPermissions.isAvailable(appContext, Uri.parse(treeUri))
    override fun displayName(treeUri: String) = SafPermissions.displayName(appContext, Uri.parse(treeUri))
}

/** A change refused because it would lose work; [message] says why. */
sealed interface LocationChange {
    data object Done : LocationChange
    data class Refused(val message: String) : LocationChange
}

class LocationRepository(
    private val locations: LocationDao,
    private val photos: RawPhotoDao,
    private val access: LocationAccess,
    private val flushXmp: suspend () -> Unit,
    /** Whether anything other than this Location still needs the grant (a card's volume entry). */
    private val isGrantInUse: suspend (treeUri: String) -> Boolean = { false },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeAll(): Flow<List<LibraryLocation>> = locations.observeAll()

    suspend fun getAll(): List<LibraryLocation> = locations.getAll()

    suspend fun get(id: Long): LibraryLocation? = locations.getById(id)

    /** Adds the picked tree, or returns the Location it already is. Null if no grant could be kept. */
    suspend fun add(treeUri: String): LibraryLocation? = withContext(Dispatchers.IO) {
        locations.getByTreeUri(treeUri)?.let { return@withContext it }
        if (!access.takePersistable(treeUri)) return@withContext null
        insert(treeUri, access.displayName(treeUri))
    }

    /** The demo library is a pseudo-Location: same screens, nothing on external storage. */
    suspend fun demo(): LibraryLocation =
        locations.getByTreeUri(DEMO_ROOT_URI) ?: insert(DEMO_ROOT_URI, DEMO_NAME)

    private suspend fun insert(treeUri: String, name: String): LibraryLocation {
        val now = clock()
        val location = LibraryLocation(treeUri = treeUri, displayName = name, addedAt = now, lastOpenedAt = now)
        val id = locations.insert(location)
        return if (id > 0) location.copy(id = id) else checkNotNull(locations.getByTreeUri(treeUri))
    }

    suspend fun rename(id: Long, displayName: String) {
        val name = displayName.trim()
        if (name.isNotEmpty()) locations.rename(id, name)
    }

    suspend fun touch(id: Long) = locations.touch(id, clock())

    suspend fun isAvailable(location: LibraryLocation): Boolean =
        location.isDemo || withContext(Dispatchers.IO) { access.isAvailable(location.treeUri) }

    /**
     * Points a Location at a new tree. Its rows are dropped and the caller rescans: culling state
     * lives in the sidecars, so it comes back with the files.
     */
    suspend fun relink(id: Long, newTreeUri: String): LocationChange = withContext(Dispatchers.IO) {
        val location = locations.getById(id) ?: return@withContext LocationChange.Done
        if (location.treeUri == newTreeUri) return@withContext LocationChange.Done
        locations.getByTreeUri(newTreeUri)?.let {
            return@withContext LocationChange.Refused("That folder is already the Location ${it.displayName}.")
        }
        unsavedRefusal(location, "relinking")?.let { return@withContext it }
        if (!access.takePersistable(newTreeUri)) {
            return@withContext LocationChange.Refused("Android didn't grant access to that folder.")
        }

        photos.deleteByRoot(location.treeUri)
        locations.updateTreeUri(id, newTreeUri)
        releaseUnlessUsed(location.treeUri)
        LocationChange.Done
    }

    /** Forgets the Location and its catalog rows. The files themselves are left alone. */
    suspend fun remove(id: Long): LocationChange = withContext(Dispatchers.IO) {
        val location = locations.getById(id) ?: return@withContext LocationChange.Done
        unsavedRefusal(location, "removing")?.let { return@withContext it }

        photos.deleteByRoot(location.treeUri)
        locations.delete(location)
        if (!location.isDemo) releaseUnlessUsed(location.treeUri)
        LocationChange.Done
    }

    private suspend fun releaseUnlessUsed(treeUri: String) {
        if (!isGrantInUse(treeUri)) access.release(treeUri)
    }

    /** Ratings waiting for a sidecar exist nowhere else, so they have to land first. */
    private suspend fun unsavedRefusal(location: LibraryLocation, action: String): LocationChange.Refused? {
        flushXmp()
        val unsaved = photos.countUnsaved(location.treeUri)
        if (unsaved == 0) return null
        val noun = if (unsaved == 1) "photo has" else "photos have"
        return LocationChange.Refused(
            "$unsaved $noun changes not saved to ${location.displayName} yet. " +
                "Reconnect it and let them finish before $action it.",
        )
    }

    private companion object {
        const val DEMO_NAME = "Demo library"
    }
}
