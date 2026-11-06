package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.shehran.rawcull.data.model.RecentSource
import dev.shehran.rawcull.data.model.RecentSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("rawcull_settings")

data class StoredFolder(val uri: String, val displayName: String)

/** Small key-value app settings, backed by DataStore. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val recentSources: Flow<List<RecentSource>> =
        dataStore.data.map { decodeRecents(it[KEY_RECENT_SOURCES]) }

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
        val KEY_LAST_SOURCE_URI = stringPreferencesKey("last_source_uri")
        val KEY_LAST_SOURCE_NAME = stringPreferencesKey("last_source_name")
        val KEY_LAST_DESTINATION_URI = stringPreferencesKey("last_destination_uri")
        val KEY_LAST_DESTINATION_NAME = stringPreferencesKey("last_destination_name")

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
