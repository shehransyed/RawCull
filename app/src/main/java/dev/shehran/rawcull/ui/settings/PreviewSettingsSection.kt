package dev.shehran.rawcull.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.ui.components.WarmupRow
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextSecondary

/** "256 MB", "1 GB": the cache limit choices (D6). */
fun cacheLimitLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

/** Settings → Previews: the thumbnail cache (D6) and building thumbnails ahead of time (D12). */
@Composable
fun PreviewSettingsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column {
        val used = Formatter.formatShortFileSize(context, uiState.previewCacheBytes)
        val limit = cacheLimitLabel(uiState.previewCacheLimitMb)
        Text("Thumbnail cache", color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "$used of $limit used. Older thumbnails make way for new ones.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("settings_preview_cache_used"),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        ) {
            SettingsRepository.PREVIEW_CACHE_LIMITS_MB.forEach { mb ->
                FilterChip(
                    selected = uiState.previewCacheLimitMb == mb,
                    onClick = { viewModel.setPreviewCacheLimit(mb) },
                    label = { Text(cacheLimitLabel(mb)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("preview_cache_limit_$mb"),
                )
            }
        }
        TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("settings_clear_preview_cache")) {
            Text("Clear preview cache", color = PrimaryCyan)
        }

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
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear preview cache?") },
            text = { Text("Thumbnails are made again from your photos as you browse. Your photos, ratings and sidecars aren't touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearPreviewCache()
                    },
                    modifier = Modifier.testTag("confirm_clear_preview_cache"),
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Debug builds only: the loupe's timing overlay and the D13 benchmark. */
@Composable
fun PreviewDeveloperSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val window = LocalWindowInfo.current.containerSize
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show preview timing", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text("Source, size, cache level and milliseconds over the loupe.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = uiState.showPreviewTiming,
                onCheckedChange = viewModel::setShowPreviewTiming,
                modifier = Modifier.testTag("settings_show_preview_timing"),
            )
        }
        val benchmark = uiState.benchmark
        if (benchmark.isRunning) {
            Text("Benchmarking… ${benchmark.done} of ${benchmark.total}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { if (benchmark.total == 0) 0f else benchmark.done.toFloat() / benchmark.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // The loupe fills about the window, so that's the fit size measured.
            TextButton(
                onClick = { viewModel.benchmarkFolder(window.width, window.height) },
                modifier = Modifier.testTag("settings_benchmark_previews"),
            ) { Text("Benchmark previews in this folder", color = PrimaryCyan) }
        }
    }

    BenchmarkDialog(uiState, viewModel)
}

@Composable
private fun BenchmarkDialog(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val table = uiState.benchmark.table ?: return
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = viewModel::dismissBenchmark,
        title = { Text("Preview benchmark") },
        text = {
            Text(
                text = table,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .testTag("benchmark_table"),
            )
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(table)) }, modifier = Modifier.testTag("benchmark_copy")) {
                Text("Copy table")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissBenchmark) { Text("Close") } },
    )
}
