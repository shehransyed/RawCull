package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.shehran.rawcull.data.model.RecentSource
import dev.shehran.rawcull.data.model.RecentSources
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("rawcull_settings")

data class StoredFolder(val uri: String, val displayName: String)

/** Where the Library was when the app last ran. */
data class LibrarySession(
    val activeLocationId: Long? = null,
    val activeFolder: String = "",
    val selectedAssetId: String? = null,
)

data class ImportPrefs(
    val verifyMode: VerifyMode = VerifyMode.FULL,
    val deleteFromCardAfterImport: Boolean = false,
    val hideAlreadyImported: Boolean = true,
    val lastPresetId: Long? = null,
)

/** Small key-value app settings, backed by DataStore. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val recentSources: Flow<List<RecentSource>> =
        dataStore.data.map { decodeRecents(it[KEY_RECENT_SOURCES]) }

    val xmpPrefs: Flow<XmpPrefs> = dataStore.data.map {
        XmpPrefs(rejectAsNegativeRating = it[KEY_REJECT_AS_NEGATIVE_RATING] ?: true)
    }

    val importPrefs: Flow<ImportPrefs> = dataStore.data.map { prefs ->
        ImportPrefs(
            verifyMode = prefs[KEY_VERIFY_MODE]?.let { runCatching { VerifyMode.valueOf(it) }.getOrNull() } ?: VerifyMode.FULL,
            deleteFromCardAfterImport = prefs[KEY_DELETE_FROM_CARD] ?: false,
            hideAlreadyImported = prefs[KEY_HIDE_ALREADY_IMPORTED] ?: true,
            lastPresetId = prefs[KEY_LAST_PRESET_ID],
        )
    }

    /** Which tree each volume UUID was granted, so a re-inserted card opens without the picker. */
    val volumeGrants: Flow<Map<String, String>> = dataStore.data.map { decodeGrants(it[KEY_VOLUME_GRANTS]) }

    suspend fun librarySession(): LibrarySession {
        val prefs = dataStore.data.first()
        return LibrarySession(
            activeLocationId = prefs[KEY_ACTIVE_LOCATION_ID],
            activeFolder = prefs[KEY_ACTIVE_FOLDER].orEmpty(),
            selectedAssetId = prefs[KEY_SELECTED_ASSET_ID],
        )
    }

    suspend fun setActiveLocation(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_LOCATION_ID) else prefs[KEY_ACTIVE_LOCATION_ID] = id
            prefs.remove(KEY_ACTIVE_FOLDER)
            prefs.remove(KEY_SELECTED_ASSET_ID)
        }
    }

    suspend fun setActiveFolder(folder: String) {
        dataStore.edit { it[KEY_ACTIVE_FOLDER] = folder }
    }

    suspend fun setSelectedAsset(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_ASSET_ID) else prefs[KEY_SELECTED_ASSET_ID] = id
        }
    }

    /** Which folder tree nodes are open, remembered per Location. */
    suspend fun expandedFolders(locationId: Long): Set<String> =
        dataStore.data.first()[expandedKey(locationId)].orEmpty()

    suspend fun setExpandedFolders(locationId: Long, folders: Set<String>) {
        dataStore.edit { it[expandedKey(locationId)] = folders }
    }

    private fun expandedKey(locationId: Long) = stringSetPreferencesKey("expanded_folders_$locationId")

    suspend fun setVerifyMode(mode: VerifyMode) {
        dataStore.edit { it[KEY_VERIFY_MODE] = mode.name }
    }

    suspend fun setDeleteFromCardAfterImport(enabled: Boolean) {
        dataStore.edit { it[KEY_DELETE_FROM_CARD] = enabled }
    }

    suspend fun setHideAlreadyImported(enabled: Boolean) {
        dataStore.edit { it[KEY_HIDE_ALREADY_IMPORTED] = enabled }
    }

    suspend fun setLastPresetId(id: Long) {
        dataStore.edit { it[KEY_LAST_PRESET_ID] = id }
    }

    suspend fun setVolumeGrant(volumeUuid: String, treeUri: String) {
        dataStore.edit { prefs ->
            prefs[KEY_VOLUME_GRANTS] = encodeGrants(decodeGrants(prefs[KEY_VOLUME_GRANTS]) + (volumeUuid to treeUri))
        }
    }

    suspend fun removeVolumeGrant(volumeUuid: String) {
        dataStore.edit { prefs ->
            prefs[KEY_VOLUME_GRANTS] = encodeGrants(decodeGrants(prefs[KEY_VOLUME_GRANTS]) - volumeUuid)
        }
    }

    suspend fun setRejectAsNegativeRating(enabled: Boolean) {
        dataStore.edit { it[KEY_REJECT_AS_NEGATIVE_RATING] = enabled }
    }

    suspend fun lastSource(): StoredFolder? = dataStore.data.first().folder(KEY_LAST_SOURCE_URI, KEY_LAST_SOURCE_NAME)

    suspend fun lastDestination(): StoredFolder? =
        dataStore.data.first().folder(KEY_LAST_DESTINATION_URI, KEY_LAST_DESTINATION_NAME)

    /** Records [source] as the last and most recent source; returns the entries that fell off the list. */
    suspend fun recordSourceOpened(source: RecentSource): List<RecentSource> {
        var evicted = emptyList<RecentSource>()
        dataStore.edit { prefs ->
            val update = RecentSources.add(decodeRecents(prefs[KEY_RECENT_SOURCES]), source)
            evicted = update.evicted
            prefs[KEY_RECENT_SOURCES] = encodeRecents(update.recents)
            prefs[KEY_LAST_SOURCE_URI] = source.uri
            prefs[KEY_LAST_SOURCE_NAME] = source.displayName
        }
        return evicted
    }

    /** After "Close source" the next launch goes to Welcome instead of reopening it. */
    suspend fun clearLastSource() {
        dataStore.edit {
            it.remove(KEY_LAST_SOURCE_URI)
            it.remove(KEY_LAST_SOURCE_NAME)
        }
    }

    suspend fun setLastDestination(folder: StoredFolder) {
        dataStore.edit {
            it[KEY_LAST_DESTINATION_URI] = folder.uri
            it[KEY_LAST_DESTINATION_NAME] = folder.displayName
        }
    }

    /** Drops recents whose permission is gone: nothing can be done with them any more. */
    suspend fun pruneRecents(persistedUris: Set<String>) {
        dataStore.edit { prefs ->
            val recents = decodeRecents(prefs[KEY_RECENT_SOURCES])
            val kept = recents.filter { it.uri in persistedUris }
            if (kept.size != recents.size) prefs[KEY_RECENT_SOURCES] = encodeRecents(kept)
        }
    }

    private fun Preferences.folder(
        uriKey: Preferences.Key<String>,
        nameKey: Preferences.Key<String>,
    ): StoredFolder? = this[uriKey]?.let { StoredFolder(it, this[nameKey].orEmpty()) }

    internal companion object {
        val KEY_RECENT_SOURCES = stringPreferencesKey("recent_sources")
        val KEY_REJECT_AS_NEGATIVE_RATING = booleanPreferencesKey("reject_as_negative_rating")
        val KEY_LAST_SOURCE_URI = stringPreferencesKey("last_source_uri")
        val KEY_LAST_SOURCE_NAME = stringPreferencesKey("last_source_name")
        val KEY_LAST_DESTINATION_URI = stringPreferencesKey("last_destination_uri")
        val KEY_LAST_DESTINATION_NAME = stringPreferencesKey("last_destination_name")
        val KEY_ACTIVE_LOCATION_ID = longPreferencesKey("active_location_id")
        val KEY_ACTIVE_FOLDER = stringPreferencesKey("active_folder")
        val KEY_SELECTED_ASSET_ID = stringPreferencesKey("selected_asset_id")
        val KEY_VERIFY_MODE = stringPreferencesKey("verify_mode")
        val KEY_DELETE_FROM_CARD = booleanPreferencesKey("delete_from_card_after_import")
        val KEY_HIDE_ALREADY_IMPORTED = booleanPreferencesKey("hide_already_imported")
        val KEY_LAST_PRESET_ID = longPreferencesKey("last_preset_id")
        val KEY_VOLUME_GRANTS = stringPreferencesKey("volume_grants")

        fun encodeGrants(grants: Map<String, String>): String {
            val json = JSONObject()
            grants.forEach { (uuid, treeUri) -> json.put(uuid, treeUri) }
            return json.toString()
        }

        fun decodeGrants(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val parsed = JSONObject(json)
                parsed.keys().asSequence().associateWith { parsed.getString(it) }
            } catch (e: JSONException) {
                emptyMap()
            }
        }

        fun encodeRecents(recents: List<RecentSource>): String {
            val array = JSONArray()
            recents.forEach { recent ->
                array.put(
                    JSONObject()
                        .put("uri", recent.uri)
                        .put("displayName", recent.displayName)
                        .put("lastOpenedAt", recent.lastOpenedAt)
                )
            }
            return array.toString()
        }

        fun decodeRecents(json: String?): List<RecentSource> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    RecentSource(
                        uri = item.getString("uri"),
                        displayName = item.getString("displayName"),
                        lastOpenedAt = item.getLong("lastOpenedAt"),
                    )
                }
            } catch (e: JSONException) {
                emptyList()
            }
        }
    }
}
