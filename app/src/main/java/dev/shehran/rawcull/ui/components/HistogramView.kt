package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.repository.HistogramMode
import dev.shehran.rawcull.ui.theme.HistogramBlue
import dev.shehran.rawcull.ui.theme.HistogramGreen
import dev.shehran.rawcull.ui.theme.HistogramGrid
import dev.shehran.rawcull.ui.theme.HistogramLuma
import dev.shehran.rawcull.ui.theme.HistogramPlot
import dev.shehran.rawcull.ui.theme.HistogramRed
import dev.shehran.rawcull.ui.theme.HistogramSurface
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.util.HistogramData
import dev.shehran.rawcull.util.histogramHeights

@Composable
fun HistogramView(
    histogram: HistogramData?,
    mode: HistogramMode,
    log: Boolean,
    onCycleMode: () -> Unit,
    onToggleLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(HistogramSurface)
            .padding(12.dp)
            .testTag("histogram_view")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${mode.label.uppercase()} HISTOGRAM",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if ((histogram?.shadowClippingCount ?: 0) > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(PrimaryCyan))
                        Text(
                            text = " Shadows",
                            fontSize = 10.sp,
                            color = PrimaryCyan
                        )
                    }
                }
                if ((histogram?.highlightClippingCount ?: 0) > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(RawCullTheme.colors.reject))
                        Text(
                            text = " Highlights",
                            fontSize = 10.sp,
                            color = RawCullTheme.colors.reject
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(HistogramPlot)
                .clickable(onClickLabel = "Change histogram channels", onClick = onCycleMode)
                .testTag("histogram_plot")
        ) {
            HistogramPlot(histogram, mode, log, Modifier.matchParentSize())
        }
        FilterChip(
            selected = log,
            onClick = onToggleLog,
            label = { Text("Log") },
            modifier = Modifier.padding(top = 8.dp).testTag("histogram_log_chip"),
        )
    }
}

/** The grid and the channel curves, shared by the inspector and the overlay. */
@Composable
fun HistogramPlot(histogram: HistogramData?, mode: HistogramMode, log: Boolean, modifier: Modifier = Modifier) {
    val heights = remember(histogram, mode, log) { histogram?.let { histogramHeights(it, mode, log) } }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(HistogramGrid, Offset(w * f, 0f), Offset(w * f, h), strokeWidth = 1f)
        }
        drawLine(HistogramGrid, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)

        if (heights == null) return@Canvas
        val stepX = w / 255f
        fun draw(bins: FloatArray?, color: Color) {
            if (bins == null) return
            val path = Path()
            path.moveTo(0f, h - bins[0] * h)
            for (i in 1..255) path.lineTo(i * stepX, h - bins[i] * h)
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
        draw(heights.luma, HistogramLuma)
        draw(heights.red, HistogramRed)
        draw(heights.green, HistogramGreen)
        draw(heights.blue, HistogramBlue)
    }
}
