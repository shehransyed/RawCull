package dev.shehran.rawcull.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewingPrefsTest {
    @Test
    fun emptyStoreGivesDefaults() {
        val prefs = ViewingPrefs.from(mutablePreferencesOf())
        assertEquals(5, prefs.slideshowIntervalS)
        assertEquals(true, prefs.slideshowLoop)
        assertEquals(true, prefs.slideshowCrossfade)
        assertEquals(PeakingSensitivity.MEDIUM, prefs.peakingSensitivity)
        assertEquals(PeakingColor.RED, prefs.peakingColor)
        assertEquals(HistogramMode.RGB_LUMA, prefs.histogramMode)
        assertEquals(false, prefs.histogramLog)
        assertEquals(false, prefs.histogramOverlay)
        assertEquals(ViewingPrefs(), prefs)
    }

    @Test
    fun everyKeyRoundTrips() {
        val changed = ViewingPrefs(
            slideshowIntervalS = 10,
            slideshowLoop = false,
            slideshowCrossfade = false,
            peakingSensitivity = PeakingSensitivity.HIGH,
            peakingColor = PeakingColor.CYAN,
            histogramMode = HistogramMode.LUMA,
            histogramLog = true,
            histogramOverlay = true,
        )
        val store = mutablePreferencesOf()
        ViewingPrefs.write(store, changed)
        assertEquals(changed, ViewingPrefs.from(store))
    }

    @Test
    fun eachIntervalAndEnumValueRoundTrips() {
        ViewingPrefs.INTERVALS_S.forEach { s ->
            val store = mutablePreferencesOf().also { ViewingPrefs.write(it, ViewingPrefs(slideshowIntervalS = s)) }
            assertEquals(s, ViewingPrefs.from(store).slideshowIntervalS)
        }
        PeakingSensitivity.entries.forEach { v ->
            val store = mutablePreferencesOf().also { ViewingPrefs.write(it, ViewingPrefs(peakingSensitivity = v)) }
            assertEquals(v, ViewingPrefs.from(store).peakingSensitivity)
        }
        PeakingColor.entries.forEach { v ->
            val store = mutablePreferencesOf().also { ViewingPrefs.write(it, ViewingPrefs(peakingColor = v)) }
            assertEquals(v, ViewingPrefs.from(store).peakingColor)
        }
        HistogramMode.entries.forEach { v ->
            val store = mutablePreferencesOf().also { ViewingPrefs.write(it, ViewingPrefs(histogramMode = v)) }
            assertEquals(v, ViewingPrefs.from(store).histogramMode)
        }
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val store = mutablePreferencesOf(
            stringPreferencesKey("peaking_sensitivity") to "EXTREME",
            stringPreferencesKey("peaking_color") to "magenta",
            stringPreferencesKey("histogram_mode") to "",
        )
        store[ViewingPrefs.KEY_INTERVAL] = 7
        val prefs = ViewingPrefs.from(store)
        assertEquals(PeakingSensitivity.MEDIUM, prefs.peakingSensitivity)
        assertEquals(PeakingColor.RED, prefs.peakingColor)
        assertEquals(HistogramMode.RGB_LUMA, prefs.histogramMode)
        assertEquals(5, prefs.slideshowIntervalS)
    }

    @Test
    fun histogramModeCycles() {
        assertEquals(HistogramMode.RGB, HistogramMode.RGB_LUMA.next())
        assertEquals(HistogramMode.LUMA, HistogramMode.RGB.next())
        assertEquals(HistogramMode.RGB_LUMA, HistogramMode.LUMA.next())
    }
}
