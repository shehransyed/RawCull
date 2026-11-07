package dev.shehran.rawcull.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A recent source plus whether its volume is connected right now. */
data class RecentSourceUi(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long,
    val isAvailable: Boolean,
)

@Composable
fun WelcomeScreen(
    recents: List<RecentSourceUi>,
    picker: SourcePicker,
    onOpenRecent: (String) -> Unit,
    onLoadDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090D))
            .verticalScroll(rememberScrollState())
            .testTag("welcome_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0284C7)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CameraRoll, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "RAWCULL",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Open a card or folder to start culling.",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = picker.openCard,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("welcome_open_card_button"),
            ) {
                Icon(Icons.Default.Usb, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (picker.noCardDetected) "No card detected — Pick folder" else "Open SD card / USB drive",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = picker.pickFolder,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("welcome_pick_folder_button"),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pick a folder", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }

            if (recents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "RECENT SOURCES",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recents.forEach { recent ->
                        RecentSourceRow(recent, onClick = { onOpenRecent(recent.uri) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            TextButton(onClick = onLoadDemo, modifier = Modifier.testTag("welcome_demo_button")) {
                Text("Try the demo shoot", color = Color(0xFF94A3B8))
            }
        }
    }
}

@Composable
private fun RecentSourceRow(recent: RecentSourceUi, onClick: () -> Unit) {
    val nameColor = if (recent.isAvailable) Color.White else Color(0xFF64748B)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F141B))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("recent_source_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = recent.displayName,
            color = nameColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (recent.isAvailable) relativeAge(recent.lastOpenedAt) else "not connected",
            color = Color(0xFF64748B),
            fontSize = 12.sp,
        )
    }
}

private fun relativeAge(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
