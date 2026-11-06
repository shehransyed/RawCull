package dev.shehran.rawcull.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("rawcull_settings")

/** Small key-value app settings, backed by DataStore. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val lastSourceUri: Flow<String?> = dataStore.data.map { it[KEY_LAST_SOURCE_URI] }

    suspend fun setLastSourceUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_LAST_SOURCE_URI) else prefs[KEY_LAST_SOURCE_URI] = uri
        }
    }

    private companion object {
        val KEY_LAST_SOURCE_URI = stringPreferencesKey("last_source_uri")
    }
}
