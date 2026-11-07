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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
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
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.ControlSurface
import dev.shehran.rawcull.ui.theme.DotOutlineWhite
import dev.shehran.rawcull.ui.theme.PanelSurface
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.StarEmpty
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Undo, redo and the auto-advance toggle, which go wherever the culling buttons go. */
class CullingHistory(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val autoAdvance: Boolean,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onToggleAutoAdvance: () -> Unit,
)

@Composable
private fun HistoryButtons(history: CullingHistory) {
    IconButton(onClick = history.onUndo, enabled = history.canUndo, modifier = Modifier.size(Dimens.TouchTarget).testTag("undo_button")) {
        Icon(
            Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            tint = if (history.canUndo) Color.White else StarEmpty,
        )
    }
    IconButton(onClick = history.onRedo, enabled = history.canRedo, modifier = Modifier.size(Dimens.TouchTarget).testTag("redo_button")) {
        Icon(
            Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
            tint = if (history.canRedo) Color.White else StarEmpty,
        )
    }
    IconButton(
        onClick = history.onToggleAutoAdvance,
        modifier = Modifier.size(Dimens.TouchTarget).testTag("auto_advance_toggle"),
    ) {
        Icon(
            Icons.Default.FastForward,
            contentDescription = if (history.autoAdvance) "Auto-advance on" else "Auto-advance off",
            tint = if (history.autoAdvance) PrimaryCyan else TextMuted,
        )
    }
}

@Composable
fun CullingActionBar(
    cullStatus: CullStatus,
    rating: Int,
    colorLabel: ColorLabel,
    onTogglePick: () -> Unit,
    onToggleReject: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (ColorLabel) -> Unit,
    history: CullingHistory,
    modifier: Modifier = Modifier,
    dense: Boolean = false, // Drops the labels, which don't fit a 360 dp phone next to 48 dp targets
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag("culling_action_bar"),
        color = PanelSurface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(if (dense) 8.dp else 12.dp)) {
            if (!dense) {
                Text(
                    text = "CULLING & RATING",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
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
                    targetValue = if (isPick) RawCullTheme.colors.pick else ControlSurface,
                    label = "pickBg"
                )
                val pickText = if (isPick) Color.Black else RawCullTheme.colors.pick

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.TouchTarget)
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
                        Spacer(modifier = Modifier.width(if (dense) 2.dp else 6.dp))
                        Text(
                            text = if (dense) "PICK" else "PICK [P]",
                            fontWeight = FontWeight.Bold,
                            fontSize = if (dense) 12.sp else 13.sp,
                            color = pickText
                        )
                    }
                }

                // Reject Button
                val isReject = cullStatus == CullStatus.REJECT
                val rejectBg by animateColorAsState(
                    targetValue = if (isReject) RawCullTheme.colors.reject else ControlSurface,
                    label = "rejectBg"
                )
                val rejectText = if (isReject) Color.White else RawCullTheme.colors.reject

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.TouchTarget)
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
                        Spacer(modifier = Modifier.width(if (dense) 2.dp else 6.dp))
                        Text(
                            text = if (dense) "REJECT" else "REJECT [X]",
                            fontWeight = FontWeight.Bold,
                            fontSize = if (dense) 12.sp else 13.sp,
                            color = rejectText
                        )
                    }
                }

                if (dense) HistoryButtons(history)
            }

            if (!dense) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) { HistoryButtons(history) }
            }

            Spacer(modifier = Modifier.height(if (dense) 2.dp else 8.dp))

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
                        color = TextSecondary
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
                                .size(Dimens.TouchTarget)
                                .testTag("star_rating_$star")
                        ) {
                            Icon(
                                imageVector = if (isFilled) Icons.Default.Star else Icons.Outlined.Star,
                                contentDescription = "$star Stars",
                                tint = if (isFilled) RawCullTheme.colors.star else StarEmpty,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    if (rating > 0) {
                        IconButton(
                            onClick = { onSetRating(0) },
                            modifier = Modifier.size(Dimens.TouchTarget).testTag("clear_rating_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RemoveCircleOutline,
                                contentDescription = "Clear Rating",
                                tint = TextMuted,
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
                        color = TextSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabelChoices.forEach { label ->
                        ColorLabelDot(RawCullTheme.colors.label(label), colorLabel == label, label) { onSetColorLabel(label) }
                    }

                    if (colorLabel != ColorLabel.NONE) {
                        IconButton(
                            onClick = { onSetColorLabel(ColorLabel.NONE) },
                            modifier = Modifier.size(Dimens.TouchTarget).testTag("clear_color_label")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RemoveCircleOutline,
                                contentDescription = "Clear Color",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private val LabelChoices = listOf(ColorLabel.RED, ColorLabel.YELLOW, ColorLabel.GREEN, ColorLabel.BLUE, ColorLabel.PURPLE)

/** The dot is small, but the touch target around it is the full 48 dp. */
@Composable
private fun ColorLabelDot(color: Color, isSelected: Boolean, label: ColorLabel, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dimens.TouchTarget)
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
                    color = if (isSelected) Color.White else DotOutlineWhite,
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
    history: CullingHistory,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(Dimens.SideBarWidth)
            .fillMaxHeight()
            .background(PanelSurface)
            .verticalScroll(rememberScrollState())
            .testTag("culling_side_bar"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HistoryButtons(history)
        IconButton(onClick = onTogglePick, modifier = Modifier.size(Dimens.TouchTarget).testTag("pick_button")) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Pick Photo",
                tint = if (cullStatus == CullStatus.PICK) Color.White else RawCullTheme.colors.pick,
                modifier = Modifier
                    .background(if (cullStatus == CullStatus.PICK) RawCullTheme.colors.pick else Color.Transparent, CircleShape)
                    .padding(4.dp),
            )
        }
        IconButton(onClick = onToggleReject, modifier = Modifier.size(Dimens.TouchTarget).testTag("reject_button")) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Reject Photo",
                tint = if (cullStatus == CullStatus.REJECT) Color.White else RawCullTheme.colors.reject,
                modifier = Modifier
                    .background(if (cullStatus == CullStatus.REJECT) RawCullTheme.colors.reject else Color.Transparent, CircleShape)
                    .padding(4.dp),
            )
        }
        for (star in 1..5) {
            IconButton(onClick = { onSetRating(star) }, modifier = Modifier.size(Dimens.TouchTarget).testTag("star_rating_$star")) {
                Icon(
                    imageVector = if (star <= rating) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = "$star Stars",
                    tint = if (star <= rating) RawCullTheme.colors.star else StarEmpty,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        LabelChoices.forEach { label ->
            ColorLabelDot(RawCullTheme.colors.label(label), colorLabel == label, label) { onSetColorLabel(label) }
        }
    }
}
