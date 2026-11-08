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
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject
import androidx.datastore.preferences.core.intPreferencesKey

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("rawcull_settings")

/** Where the Library was when the app last ran. */
data class LibrarySession(
    val activeLocationId: Long? = null,
    val activeFolder: String = "",
    val selectedAssetId: String? = null,
    val activeCollectionId: Long? = null,
)

/** How culling behaves on this device. */
data class CullingPrefs(val autoAdvance: Boolean = false, val swipeToFlag: Boolean = true)

data class ImportPrefs(
    val verifyMode: VerifyMode = VerifyMode.FULL,
    val deleteFromCardAfterImport: Boolean = false,
    val hideAlreadyImported: Boolean = true,
    val lastPresetId: Long? = null,
)

/** Small key-value app settings, backed by DataStore. Tests pass their own [dataStore]. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

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

    val cullingPrefs: Flow<CullingPrefs> = dataStore.data.map { prefs ->
        CullingPrefs(
            autoAdvance = prefs[KEY_AUTO_ADVANCE] ?: false,
            swipeToFlag = prefs[KEY_SWIPE_TO_FLAG] ?: true,
        )
    }

    /** D6: how big the thumbnail cache may get. */
    val previewCacheLimitMb: Flow<Int> = dataStore.data.map { it[KEY_PREVIEW_CACHE_LIMIT_MB] ?: DEFAULT_PREVIEW_CACHE_LIMIT_MB }

    /** Debug builds: timings over the loupe. */
    val showPreviewTiming: Flow<Boolean> = dataStore.data.map { it[KEY_SHOW_PREVIEW_TIMING] ?: false }

    /** R6 D11: slideshow, focus peaking and histogram choices. */
    val viewingPrefs: Flow<ViewingPrefs> = dataStore.data.map { ViewingPrefs.from(it) }

    /** Changes viewing choices read-modify-write, so keys never clobber each other. */
    suspend fun updateViewingPrefs(change: (ViewingPrefs) -> ViewingPrefs) {
        dataStore.edit { prefs -> ViewingPrefs.write(prefs, change(ViewingPrefs.from(prefs))) }
    }

    suspend fun setPreviewCacheLimitMb(mb: Int) {
        dataStore.edit { it[KEY_PREVIEW_CACHE_LIMIT_MB] = mb }
    }

    suspend fun setShowPreviewTiming(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_PREVIEW_TIMING] = enabled }
    }

    /** Which tree each volume UUID was granted, so a re-inserted card opens without the picker. */
    val volumeGrants: Flow<Map<String, String>> = dataStore.data.map { decodeGrants(it[KEY_VOLUME_GRANTS]) }

    suspend fun librarySession(): LibrarySession {
        val prefs = dataStore.data.first()
        return LibrarySession(
            activeLocationId = prefs[KEY_ACTIVE_LOCATION_ID],
            activeFolder = prefs[KEY_ACTIVE_FOLDER].orEmpty(),
            selectedAssetId = prefs[KEY_SELECTED_ASSET_ID],
            activeCollectionId = prefs[KEY_ACTIVE_COLLECTION_ID],
        )
    }

    suspend fun setActiveLocation(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_LOCATION_ID) else prefs[KEY_ACTIVE_LOCATION_ID] = id
            prefs.remove(KEY_ACTIVE_FOLDER)
            prefs.remove(KEY_ACTIVE_COLLECTION_ID)
            prefs.remove(KEY_SELECTED_ASSET_ID)
        }
    }

    suspend fun setActiveFolder(folder: String) {
        dataStore.edit { it[KEY_ACTIVE_FOLDER] = folder }
    }

    /** D5: the collection the Library was showing; null when it was showing a folder or everything. */
    suspend fun setActiveCollection(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_COLLECTION_ID) else prefs[KEY_ACTIVE_COLLECTION_ID] = id
        }
    }

    /** D6: where B adds to. Collections belong to one Location, so a stale id simply matches nothing. */
    val targetCollectionId: Flow<Long?> = dataStore.data.map { it[KEY_TARGET_COLLECTION_ID] }

    suspend fun setTargetCollection(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_TARGET_COLLECTION_ID) else prefs[KEY_TARGET_COLLECTION_ID] = id
        }
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

    /** Filters are remembered per Location, since each library is culled differently. */
    suspend fun filters(locationId: Long): FilterOptions =
        FilterOptionsCodec.decode(dataStore.data.first()[filtersKey(locationId)])

    suspend fun setFilters(locationId: Long, filter: FilterOptions) {
        dataStore.edit { it[filtersKey(locationId)] = FilterOptionsCodec.encode(filter) }
    }

    suspend fun filtersEnabled(locationId: Long): Boolean =
        dataStore.data.first()[filtersEnabledKey(locationId)] ?: true

    suspend fun setFiltersEnabled(locationId: Long, enabled: Boolean) {
        dataStore.edit { it[filtersEnabledKey(locationId)] = enabled }
    }

    private fun filtersKey(locationId: Long) = stringPreferencesKey("library_filters_$locationId")

    private fun filtersEnabledKey(locationId: Long) = booleanPreferencesKey("library_filters_enabled_$locationId")

    suspend fun setAutoAdvance(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_ADVANCE] = enabled }
    }

    suspend fun setSwipeToFlag(enabled: Boolean) {
        dataStore.edit { it[KEY_SWIPE_TO_FLAG] = enabled }
    }

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

    suspend fun legacyPreviewCacheCleared(): Boolean = dataStore.data.first()[KEY_LEGACY_PREVIEW_CACHE_CLEARED] ?: false

    suspend fun setLegacyPreviewCacheCleared() {
        dataStore.edit { it[KEY_LEGACY_PREVIEW_CACHE_CLEARED] = true }
    }

    /** What older versions remembered, or null once it has been dealt with. */
    suspend fun legacyFolders(): LegacyFolders? {
        val prefs = dataStore.data.first()
        val hasAny = LEGACY_KEYS.any { it in prefs }
        if (!hasAny) return null
        return LegacyFolders(destinationUri = prefs[KEY_LAST_DESTINATION_URI], sourceUri = prefs[KEY_LAST_SOURCE_URI])
    }

    suspend fun clearLegacyFolders() {
        dataStore.edit { prefs -> LEGACY_KEYS.forEach { prefs.remove(it) } }
    }

    internal companion object {
        val KEY_REJECT_AS_NEGATIVE_RATING = booleanPreferencesKey("reject_as_negative_rating")
        val KEY_LAST_SOURCE_URI = stringPreferencesKey("last_source_uri")
        val KEY_LAST_DESTINATION_URI = stringPreferencesKey("last_destination_uri")
        val LEGACY_KEYS = listOf(
            stringPreferencesKey("recent_sources"),
            KEY_LAST_SOURCE_URI,
            stringPreferencesKey("last_source_name"),
            KEY_LAST_DESTINATION_URI,
            stringPreferencesKey("last_destination_name"),
        )
        val KEY_ACTIVE_LOCATION_ID = longPreferencesKey("active_location_id")
        val KEY_ACTIVE_FOLDER = stringPreferencesKey("active_folder")
        val KEY_ACTIVE_COLLECTION_ID = longPreferencesKey("active_collection_id")
        val KEY_TARGET_COLLECTION_ID = longPreferencesKey("target_collection_id")
        val KEY_SELECTED_ASSET_ID = stringPreferencesKey("selected_asset_id")
        val KEY_VERIFY_MODE = stringPreferencesKey("verify_mode")
        val KEY_DELETE_FROM_CARD = booleanPreferencesKey("delete_from_card_after_import")
        val KEY_HIDE_ALREADY_IMPORTED = booleanPreferencesKey("hide_already_imported")
        val KEY_LAST_PRESET_ID = longPreferencesKey("last_preset_id")
        val KEY_VOLUME_GRANTS = stringPreferencesKey("volume_grants")
        val KEY_AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val KEY_SWIPE_TO_FLAG = booleanPreferencesKey("swipe_to_flag")
        val KEY_LEGACY_PREVIEW_CACHE_CLEARED = booleanPreferencesKey("legacy_preview_cache_cleared")
        val KEY_PREVIEW_CACHE_LIMIT_MB = intPreferencesKey("preview_cache_limit_mb")
        val KEY_SHOW_PREVIEW_TIMING = booleanPreferencesKey("show_preview_timing")
        const val DEFAULT_PREVIEW_CACHE_LIMIT_MB = 1024
        val PREVIEW_CACHE_LIMITS_MB = listOf(256, 512, 1024, 2048, 4096)

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
    }
}
