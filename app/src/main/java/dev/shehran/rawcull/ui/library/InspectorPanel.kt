package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.ui.components.CullingActionBar
import dev.shehran.rawcull.ui.components.CullingHistory
import dev.shehran.rawcull.ui.components.ExifInspectorPanel
import dev.shehran.rawcull.ui.components.HistogramView
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

class CullingActions(
    val onTogglePick: () -> Unit,
    val onToggleReject: () -> Unit,
    val onSetRating: (Int) -> Unit,
    val onSetColorLabel: (ColorLabel) -> Unit,
    val history: CullingHistory,
)

/** Histogram, EXIF and tags; a side pane on wide windows and a bottom sheet on narrow ones. */
@Composable
fun InspectorPanel(
    uiState: LibraryUiState,
    culling: CullingActions?,
    onEditTags: () -> Unit,
    collectionActions: CollectionInspectorActions,
    onCycleHistogramMode: () -> Unit,
    onToggleHistogramLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = uiState.currentPhoto
    Column(
        modifier = modifier
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
            .testTag("inspector_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        culling?.let {
            CullingActionBar(
                cullStatus = photo?.cullStatus ?: CullStatus.UNFLAGGED,
                rating = photo?.rating ?: 0,
                colorLabel = photo?.colorLabel ?: ColorLabel.NONE,
                onTogglePick = it.onTogglePick,
                onToggleReject = it.onToggleReject,
                onSetRating = it.onSetRating,
                onSetColorLabel = it.onSetColorLabel,
                history = it.history,
            )
        }
        HistogramView(
            histogram = uiState.currentHistogram,
            mode = uiState.viewing.histogramMode,
            log = uiState.viewing.histogramLog,
            onCycleMode = onCycleHistogramMode,
            onToggleLog = onToggleHistogramLog,
        )
        ExifInspectorPanel(photo = photo)
        TagsCard(tags = photo?.tags.orEmpty(), onEditTags = onEditTags)
        if (photo != null) CollectionsCard(uiState.photoCollections, uiState.activeCollection != null, collectionActions)
    }
}

@Composable
private fun TagsCard(tags: List<String>, onEditTags: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "KEYWORDS & TAGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                TextButton(onClick = onEditTags, modifier = Modifier.testTag("edit_tags_button")) {
                    Icon(
                        Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        tint = PrimaryCyan,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    )
                    Text("Edit Tags", fontSize = 11.sp, color = PrimaryCyan)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (tags.isEmpty()) {
                Text("No tags assigned", color = TextMuted, fontSize = 11.sp)
            } else {
                Text(tags.joinToString("  •  "), color = PrimaryCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
