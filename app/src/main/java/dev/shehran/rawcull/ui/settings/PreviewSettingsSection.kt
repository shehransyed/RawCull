package dev.shehran.rawcull.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import dev.shehran.rawcull.ui.components.WarmupRow
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Settings → Previews: building thumbnails ahead of time (D12). */
@Composable
fun PreviewSettingsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    Column {
        val location = uiState.previewLocation
        val warmup = uiState.warmup
        if (warmup.isActive) {
            WarmupRow(
                state = warmup,
                onPause = viewModel::pauseWarmup,
                onResume = viewModel::resumeWarmup,
                onCancel = viewModel::cancelWarmup,
            )
        } else if (location != null) {
            TextButton(onClick = viewModel::buildPreviews, modifier = Modifier.testTag("settings_build_previews")) {
                Text("Build previews for ${location.displayName}", color = PrimaryCyan)
            }
            Text(
                text = "Makes every thumbnail now, so scrolling never waits. It pauses while you browse or import.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Open a library location to build its previews.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
