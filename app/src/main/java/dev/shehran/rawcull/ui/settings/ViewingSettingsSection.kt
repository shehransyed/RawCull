package dev.shehran.rawcull.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.repository.HistogramMode
import dev.shehran.rawcull.data.repository.PeakingColor
import dev.shehran.rawcull.data.repository.PeakingSensitivity
import dev.shehran.rawcull.data.repository.ViewingPrefs
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Settings → Viewing: slideshow, focus peaking and histogram choices (R6 D11). */
@Composable
fun ViewingSettingsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val prefs = uiState.viewingPrefs
    Column {
        ChipGroup("Slideshow interval", "Time each photo stays on screen once it has loaded.") {
            ViewingPrefs.INTERVALS_S.forEach { s ->
                FilterChip(
                    selected = prefs.slideshowIntervalS == s,
                    onClick = { viewModel.updateViewing { it.copy(slideshowIntervalS = s) } },
                    label = { Text("$s s") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("viewing_interval_$s"),
                )
            }
        }
        ViewingSwitchRow("Loop slideshow", "Start over after the last photo. Off stops on the last one.", prefs.slideshowLoop, "viewing_loop") { on ->
            viewModel.updateViewing { it.copy(slideshowLoop = on) }
        }
        ViewingSwitchRow("Crossfade", "Fade between photos (300 ms).", prefs.slideshowCrossfade, "viewing_crossfade") { on ->
            viewModel.updateViewing { it.copy(slideshowCrossfade = on) }
        }
        ChipGroup("Focus peaking sensitivity", "How strong an edge has to be to get highlighted.") {
            PeakingSensitivity.entries.forEach { s ->
                FilterChip(
                    selected = prefs.peakingSensitivity == s,
                    onClick = { viewModel.updateViewing { it.copy(peakingSensitivity = s) } },
                    label = { Text(s.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("viewing_peaking_sensitivity_${s.name}"),
                )
            }
        }
        ChipGroup("Focus peaking colour", null) {
            PeakingColor.entries.forEach { c ->
                FilterChip(
                    selected = prefs.peakingColor == c,
                    onClick = { viewModel.updateViewing { it.copy(peakingColor = c) } },
                    label = { Text(c.label, color = RawCullTheme.colors.peaking(c)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("viewing_peaking_color_${c.name}"),
                )
            }
        }
        ChipGroup("Histogram channels", "Tap the histogram in the loupe to cycle these.") {
            HistogramMode.entries.forEach { m ->
                FilterChip(
                    selected = prefs.histogramMode == m,
                    onClick = { viewModel.updateViewing { it.copy(histogramMode = m) } },
                    label = { Text(m.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("viewing_histogram_mode_${m.name}"),
                )
            }
        }
        ViewingSwitchRow("Logarithmic histogram", "Lifts small amounts so shadows and highlights show.", prefs.histogramLog, "viewing_histogram_log") { on ->
            viewModel.updateViewing { it.copy(histogramLog = on) }
        }
    }
}

@Composable
private fun ChipGroup(title: String, detail: String?, chips: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) { chips() }
    }
}

@Composable
private fun ViewingSwitchRow(title: String, detail: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}
