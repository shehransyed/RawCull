package dev.shehran.rawcull.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus

@Composable
fun CullingActionBar(
    cullStatus: CullStatus,
    rating: Int,
    colorLabel: ColorLabel,
    onTogglePick: () -> Unit,
    onToggleReject: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (ColorLabel) -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false, // Drops the labels, which don't fit a 360 dp phone next to 48 dp targets
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag("culling_action_bar"),
        color = Color(0xFF13181F),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(if (dense) 8.dp else 12.dp)) {
            if (!dense) {
                Text(
                    text = "CULLING & RATING",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Primary Pick / Reject / Unflag Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pick Button
                val isPick = cullStatus == CullStatus.PICK
                val pickBg by animateColorAsState(
                    targetValue = if (isPick) Color(0xFF10B981) else Color(0xFF1E2630),
                    label = "pickBg"
                )
                val pickText = if (isPick) Color.Black else Color(0xFF10B981)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(pickBg)
                        .clickable { onTogglePick() }
                        .testTag("pick_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Pick Photo",
                            tint = pickText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "PICK [P]",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = pickText
                        )
                    }
                }

                // Reject Button
                val isReject = cullStatus == CullStatus.REJECT
                val rejectBg by animateColorAsState(
                    targetValue = if (isReject) Color(0xFFEF4444) else Color(0xFF1E2630),
                    label = "rejectBg"
                )
                val rejectText = if (isReject) Color.White else Color(0xFFEF4444)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(rejectBg)
                        .clickable { onToggleReject() }
                        .testTag("reject_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Reject Photo",
                            tint = rejectText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REJECT [X]",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = rejectText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (dense) 2.dp else 14.dp))

            // 0 - 5 Star Rating Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!dense) {
                    Text(
                        text = "Rating:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (star in 1..5) {
                        val isFilled = star <= rating
                        IconButton(
                            onClick = { onSetRating(star) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("star_rating_$star")
                        ) {
                            Icon(
                                imageVector = if (isFilled) Icons.Default.Star else Icons.Outlined.Star,
                                contentDescription = "$star Stars",
                                tint = if (isFilled) Color(0xFFFBBF24) else Color(0xFF475569),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    if (rating > 0) {
                        IconButton(
                            onClick = { onSetRating(0) },
                            modifier = Modifier.size(48.dp).testTag("clear_rating_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RemoveCircleOutline,
                                contentDescription = "Clear Rating",
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (!dense) Spacer(modifier = Modifier.height(10.dp))

            // Color Labels Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!dense) {
                    Text(
                        text = "Label:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val colors = listOf(
                        ColorLabel.RED to Color(0xFFEF4444),
                        ColorLabel.YELLOW to Color(0xFFF59E0B),
                        ColorLabel.GREEN to Color(0xFF10B981),
                        ColorLabel.BLUE to Color(0xFF3B82F6),
                        ColorLabel.PURPLE to Color(0xFF8B5CF6)
                    )

                    colors.forEach { (label, color) ->
                        val isSelected = colorLabel == label
                        ColorLabelDot(color, isSelected, label) { onSetColorLabel(label) }
                    }

                    if (colorLabel != ColorLabel.NONE) {
                        IconButton(
                            onClick = { onSetColorLabel(ColorLabel.NONE) },
                            modifier = Modifier.size(48.dp).testTag("clear_color_label")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RemoveCircleOutline,
                                contentDescription = "Clear Color",
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The dot is small, but the touch target around it is the full 48 dp. */
@Composable
private fun ColorLabelDot(color: Color, isSelected: Boolean, label: ColorLabel, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .testTag("color_label_${label.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) Color.White else Color(0x44FFFFFF),
                    shape = CircleShape,
                ),
        )
    }
}

/** The same controls as a narrow column, for a phone on its side where height is what's short. */
@Composable
fun CullingSideBar(
    cullStatus: CullStatus,
    rating: Int,
    colorLabel: ColorLabel,
    onTogglePick: () -> Unit,
    onToggleReject: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (ColorLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(Color(0xFF13181F))
            .verticalScroll(rememberScrollState())
            .testTag("culling_side_bar"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onTogglePick, modifier = Modifier.size(48.dp).testTag("pick_button")) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Pick Photo",
                tint = if (cullStatus == CullStatus.PICK) Color.White else Color(0xFF10B981),
                modifier = Modifier
                    .background(if (cullStatus == CullStatus.PICK) Color(0xFF10B981) else Color.Transparent, CircleShape)
                    .padding(4.dp),
            )
        }
        IconButton(onClick = onToggleReject, modifier = Modifier.size(48.dp).testTag("reject_button")) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Reject Photo",
                tint = if (cullStatus == CullStatus.REJECT) Color.White else Color(0xFFEF4444),
                modifier = Modifier
                    .background(if (cullStatus == CullStatus.REJECT) Color(0xFFEF4444) else Color.Transparent, CircleShape)
                    .padding(4.dp),
            )
        }
        for (star in 1..5) {
            IconButton(onClick = { onSetRating(star) }, modifier = Modifier.size(48.dp).testTag("star_rating_$star")) {
                Icon(
                    imageVector = if (star <= rating) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = "$star Stars",
                    tint = if (star <= rating) Color(0xFFFBBF24) else Color(0xFF475569),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        listOf(
            ColorLabel.RED to Color(0xFFEF4444),
            ColorLabel.YELLOW to Color(0xFFF59E0B),
            ColorLabel.GREEN to Color(0xFF10B981),
            ColorLabel.BLUE to Color(0xFF3B82F6),
            ColorLabel.PURPLE to Color(0xFF8B5CF6),
        ).forEach { (label, color) ->
            ColorLabelDot(color, colorLabel == label, label) { onSetColorLabel(label) }
        }
    }
}
