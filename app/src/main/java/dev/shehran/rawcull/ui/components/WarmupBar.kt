package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.data.preview.PreviewWarmup
import dev.shehran.rawcull.data.preview.WarmupState

/** Background thumbnail building. Null in previews and UI tests. */
val LocalPreviewWarmup = staticCompositionLocalOf<PreviewWarmup?> { null }

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/** "Building previews · Latest Import · 12 of 80 · about 2 min", or why it's waiting. */
fun warmupText(state: WarmupState): String {
    val parts = mutableListOf("Building previews", state.label, "${state.done} of ${state.total}")
    when {
        state.isPaused -> parts += "paused"
        state.isWaiting -> parts += "waiting"
        else -> state.etaMillis?.let { parts += eta(it) }
    }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

private fun eta(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    return if (seconds < SECONDS_PER_MINUTE) "under a minute" else "about ${(seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE} min"
}

/** A thin progress line under the Library's top bar while warm-up runs (D12). */
@Composable
fun WarmupBar(modifier: Modifier = Modifier) {
    val warmup = LocalPreviewWarmup.current ?: return
    val state by warmup.state.collectAsStateWithLifecycle()
    if (!state.isActive) return
    WarmupRow(state, onPause = warmup::pause, onResume = warmup::resume, onCancel = warmup::cancel, modifier = modifier)
}

@Composable
fun WarmupRow(
    state: WarmupState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("warmup_bar")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = warmupText(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.isPaused) {
                IconButton(onClick = onResume, modifier = Modifier.testTag("warmup_resume")) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume building previews")
                }
            } else {
                IconButton(onClick = onPause, modifier = Modifier.testTag("warmup_pause")) {
                    Icon(Icons.Default.Pause, contentDescription = "Pause building previews")
                }
            }
            IconButton(onClick = onCancel, modifier = Modifier.testTag("warmup_cancel")) {
                Icon(Icons.Default.Close, contentDescription = "Stop building previews")
            }
        }
        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
    }
}
