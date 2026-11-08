package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.repository.ViewingPrefs
import dev.shehran.rawcull.ui.theme.ScrimStrong

/** What the slideshow's controls can do (R6 D6). */
class SlideshowActions(
    val onTogglePause: () -> Unit,
    val onSetInterval: (Int) -> Unit,
    val onSetLoop: (Boolean) -> Unit,
)

/** Play or pause, the interval chips and the loop chip, shown in the full window's controls overlay. */
@Composable
fun SlideshowBar(show: SlideshowUi, prefs: ViewingPrefs, actions: SlideshowActions, modifier: Modifier = Modifier) {
    // Wraps, so it fits a 360 dp phone: play and position first, then the chips.
    FlowRow(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(ScrimStrong).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = actions.onTogglePause, modifier = Modifier.testTag("slideshow_play_pause")) {
            Icon(
                imageVector = if (show.isPaused || show.hasEnded) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (show.isPaused || show.hasEnded) "Play slideshow" else "Pause slideshow",
                tint = Color.White,
            )
        }
        Text(
            text = "${show.position + 1} / ${show.playlist.size}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterVertically).testTag("slideshow_position"),
        )
        ViewingPrefs.INTERVALS_S.forEach { seconds ->
            FilterChip(
                selected = prefs.slideshowIntervalS == seconds,
                onClick = { actions.onSetInterval(seconds) },
                label = { Text("$seconds s") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("slideshow_interval_$seconds"),
            )
        }
        FilterChip(
            selected = prefs.slideshowLoop,
            onClick = { actions.onSetLoop(!prefs.slideshowLoop) },
            label = { Text("Loop") },
            modifier = Modifier.heightIn(min = 48.dp).testTag("slideshow_loop"),
        )
    }
}
