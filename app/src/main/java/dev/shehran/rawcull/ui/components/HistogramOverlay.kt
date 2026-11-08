package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.repository.HistogramMode
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.shell.WidthClass
import dev.shehran.rawcull.ui.theme.ScrimStrong
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.util.HistogramData

/** What the translucent histogram needs; null in [LocalHistogramOverlay] means it is off (R6 D10). */
@Immutable
data class HistogramOverlayState(
    val histogram: HistogramData?,
    val mode: HistogramMode,
    val log: Boolean,
    val onCycleMode: () -> Unit,
    val onToggleLog: () -> Unit,
)

/** Set by the Library, and left null in compare, so the loupe and the full window follow it. */
val LocalHistogramOverlay = compositionLocalOf<HistogramOverlayState?> { null }

/** A small translucent histogram: tap cycles the channels, the corner chip switches the scale. */
@Composable
fun HistogramOverlay(state: HistogramOverlayState, modifier: Modifier = Modifier) {
    val compact = LocalWindowLayout.current.width == WidthClass.COMPACT
    Box(
        modifier = modifier
            .size(width = if (compact) 160.dp else 200.dp, height = if (compact) 80.dp else 100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ScrimStrong)
            .clickable(onClickLabel = "Change histogram channels", onClick = state.onCycleMode)
            .testTag("histogram_overlay"),
    ) {
        HistogramPlot(state.histogram, state.mode, state.log, Modifier.fillMaxSize().padding(4.dp))
        Text(
            text = state.mode.label,
            style = MaterialTheme.typography.labelSmall,
            color = PrimaryCyan,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            text = if (state.log) "Log" else "Lin",
            style = MaterialTheme.typography.labelSmall,
            color = PrimaryCyan,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = state.onToggleLog)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("histogram_overlay_log"),
        )
    }
}
