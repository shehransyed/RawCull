package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.util.HistogramData

@Composable
fun HistogramView(
    histogram: HistogramData?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F1419))
            .padding(12.dp)
            .testTag("histogram_view")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RGB HISTOGRAM",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if ((histogram?.shadowClippingCount ?: 0) > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                        Text(
                            text = " Shadows",
                            fontSize = 10.sp,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
                if ((histogram?.highlightClippingCount ?: 0) > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                        Text(
                            text = " Highlights",
                            fontSize = 10.sp,
                            color = Color(0xFFEF4444)
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
                .background(Color(0xFF05080C))
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                // Draw background grid lines (25%, 50%, 75%)
                val gridColor = Color(0x1AFFFFFF)
                drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(w * 0.25f, 0f), end = androidx.compose.ui.geometry.Offset(w * 0.25f, h), strokeWidth = 1f)
                drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(w * 0.50f, 0f), end = androidx.compose.ui.geometry.Offset(w * 0.50f, h), strokeWidth = 1f)
                drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(w * 0.75f, 0f), end = androidx.compose.ui.geometry.Offset(w * 0.75f, h), strokeWidth = 1f)
                drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, h * 0.5f), end = androidx.compose.ui.geometry.Offset(w, h * 0.5f), strokeWidth = 1f)

                if (histogram != null) {
                    val stepX = w / 255f

                    // Function to build path for a channel
                    fun buildChannelPath(bins: FloatArray): Path {
                        val path = Path()
                        path.moveTo(0f, h - (bins[0] * h))
                        for (i in 1..255) {
                            val x = i * stepX
                            val y = h - (bins[i] * h)
                            path.lineTo(x, y)
                        }
                        return path
                    }

                    // Draw Luminance (White translucent)
                    drawPath(
                        path = buildChannelPath(histogram.lumBins),
                        color = Color(0x55E2E8F0),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Draw Red
                    drawPath(
                        path = buildChannelPath(histogram.redBins),
                        color = Color(0x88EF4444),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Draw Green
                    drawPath(
                        path = buildChannelPath(histogram.greenBins),
                        color = Color(0x8822C55E),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Draw Blue
                    drawPath(
                        path = buildChannelPath(histogram.blueBins),
                        color = Color(0x883B82F6),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}
