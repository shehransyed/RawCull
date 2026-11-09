package dev.shehran.rawcull.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.data.model.VerifyMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R8.7 (D16): every DataStore key from R3 to R7, by its on-disk name, is written the way an older
 * build did, the store is closed and reopened on the same file (an app update), and today's
 * [SettingsRepository] still reads each value. Renaming a key makes this fail, which is the point.
 */
@RunWith(AndroidJUnit4::class)
class SettingsKeysDeviceTest {
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "settings-keys-test.preferences_pb")

    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(scope = scope) { file }

    @Test
    fun everyStoredKeyStillReads() = runBlocking {
        file.delete()
        val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store(writer).edit {
            it[booleanPreferencesKey("reject_as_negative_rating")] = false
            it[stringPreferencesKey("verify_mode")] = VerifyMode.QUICK.name
            it[booleanPreferencesKey("delete_from_card_after_import")] = true
            it[booleanPreferencesKey("hide_already_imported")] = false
            it[longPreferencesKey("last_preset_id")] = 12L
            it[longPreferencesKey("active_location_id")] = 7L
            it[stringPreferencesKey("active_folder")] = "2026/2026-11-08"
            it[longPreferencesKey("active_collection_id")] = 3L
            it[stringPreferencesKey("selected_asset_id")] = "asset-9"
            it[booleanPreferencesKey("auto_advance")] = true
            it[booleanPreferencesKey("swipe_to_flag")] = false
            it[intPreferencesKey("preview_cache_limit_mb")] = 2048
            it[booleanPreferencesKey("show_preview_timing")] = true
            it[stringPreferencesKey("volume_grants")] = """{"ABCD-1234":"content://tree/card"}"""
            it[intPreferencesKey("slideshow_interval_s")] = 10
            it[booleanPreferencesKey("slideshow_loop")] = false
            it[booleanPreferencesKey("slideshow_crossfade")] = false
            it[stringPreferencesKey("peaking_sensitivity")] = "HIGH"
            it[stringPreferencesKey("peaking_color")] = "CYAN"
            it[stringPreferencesKey("histogram_mode")] = "LUMA"
            it[booleanPreferencesKey("histogram_log")] = true
            it[booleanPreferencesKey("histogram_overlay")] = true
        }
        writer.cancel()

        val reader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsRepository(store(reader))
        assertFalse(settings.xmpPrefs.first().rejectAsNegativeRating)
        val imports = settings.importPrefs.first()
        assertEquals(VerifyMode.QUICK, imports.verifyMode)
        assertTrue(imports.deleteFromCardAfterImport)
        assertFalse(imports.hideAlreadyImported)
        assertEquals(12L, imports.lastPresetId)
        val session = settings.librarySession()
        assertEquals(7L, session.activeLocationId)
        assertEquals("2026/2026-11-08", session.activeFolder)
        assertEquals(3L, session.activeCollectionId)
        assertEquals("asset-9", session.selectedAssetId)
        val culling = settings.cullingPrefs.first()
        assertTrue(culling.autoAdvance)
        assertFalse(culling.swipeToFlag)
        assertEquals(2048, settings.previewCacheLimitMb.first())
        assertTrue(settings.showPreviewTiming.first())
        assertEquals(mapOf("ABCD-1234" to "content://tree/card"), settings.volumeGrants.first())
        val viewing = settings.viewingPrefs.first()
        assertEquals(10, viewing.slideshowIntervalS)
        assertFalse(viewing.slideshowLoop)
        assertFalse(viewing.slideshowCrossfade)
        assertEquals(PeakingSensitivity.HIGH, viewing.peakingSensitivity)
        assertEquals(PeakingColor.CYAN, viewing.peakingColor)
        assertEquals(HistogramMode.LUMA, viewing.histogramMode)
        assertTrue(viewing.histogramLog)
        assertTrue(viewing.histogramOverlay)
        reader.cancel()
        file.delete()
        Unit
    }
}
