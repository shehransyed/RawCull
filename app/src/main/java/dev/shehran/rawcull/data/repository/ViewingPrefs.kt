package dev.shehran.rawcull.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Focus peaking sensitivity (R6 D7). */
enum class PeakingSensitivity(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/** Focus peaking highlight colour (R6 D7). */
enum class PeakingColor(val label: String) { RED("Red"), YELLOW("Yellow"), CYAN("Cyan"), GREEN("Green") }

/** Which histogram channels are drawn (R6 D9). */
enum class HistogramMode(val label: String) {
    RGB_LUMA("RGB + Luma"),
    RGB("RGB"),
    LUMA("Luma");

    fun next(): HistogramMode = entries[(ordinal + 1) % entries.size]
}

/** Everything in Settings → Viewing (R6 D11). */
data class ViewingPrefs(
    val slideshowIntervalS: Int = DEFAULT_INTERVAL_S,
    val slideshowLoop: Boolean = true,
    val slideshowCrossfade: Boolean = true,
    val peakingSensitivity: PeakingSensitivity = PeakingSensitivity.MEDIUM,
    val peakingColor: PeakingColor = PeakingColor.RED,
    val histogramMode: HistogramMode = HistogramMode.RGB_LUMA,
    val histogramLog: Boolean = false,
    val histogramOverlay: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTERVAL_S = 5
        val INTERVALS_S = listOf(2, 3, 5, 10)

        val KEY_INTERVAL = intPreferencesKey("slideshow_interval_s")
        val KEY_LOOP = booleanPreferencesKey("slideshow_loop")
        val KEY_CROSSFADE = booleanPreferencesKey("slideshow_crossfade")
        val KEY_SENSITIVITY = stringPreferencesKey("peaking_sensitivity")
        val KEY_COLOR = stringPreferencesKey("peaking_color")
        val KEY_MODE = stringPreferencesKey("histogram_mode")
        val KEY_LOG = booleanPreferencesKey("histogram_log")
        val KEY_OVERLAY = booleanPreferencesKey("histogram_overlay")

        /** Reads the stored values; anything missing or unrecognised falls back to its default. */
        fun from(prefs: Preferences): ViewingPrefs = ViewingPrefs(
            slideshowIntervalS = prefs[KEY_INTERVAL]?.takeIf { it in INTERVALS_S } ?: DEFAULT_INTERVAL_S,
            slideshowLoop = prefs[KEY_LOOP] ?: true,
            slideshowCrossfade = prefs[KEY_CROSSFADE] ?: true,
            peakingSensitivity = prefs[KEY_SENSITIVITY].toEnum(PeakingSensitivity.MEDIUM),
            peakingColor = prefs[KEY_COLOR].toEnum(PeakingColor.RED),
            histogramMode = prefs[KEY_MODE].toEnum(HistogramMode.RGB_LUMA),
            histogramLog = prefs[KEY_LOG] ?: false,
            histogramOverlay = prefs[KEY_OVERLAY] ?: false,
        )

        fun write(prefs: MutablePreferences, value: ViewingPrefs) {
            prefs[KEY_INTERVAL] = value.slideshowIntervalS
            prefs[KEY_LOOP] = value.slideshowLoop
            prefs[KEY_CROSSFADE] = value.slideshowCrossfade
            prefs[KEY_SENSITIVITY] = value.peakingSensitivity.name
            prefs[KEY_COLOR] = value.peakingColor.name
            prefs[KEY_MODE] = value.histogramMode.name
            prefs[KEY_LOG] = value.histogramLog
            prefs[KEY_OVERLAY] = value.histogramOverlay
        }

        private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
            this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
    }
}
