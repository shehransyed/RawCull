package dev.shehran.rawcull.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.ui.theme.FormatCyan
import dev.shehran.rawcull.ui.theme.FormatOrange
import dev.shehran.rawcull.ui.theme.FormatPurple
import dev.shehran.rawcull.ui.theme.HairlineWhite
import dev.shehran.rawcull.ui.theme.InsetSurface
import dev.shehran.rawcull.ui.theme.PanelSurface
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.ui.theme.TextValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExifInspectorPanel(
    photo: PhotoAsset?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag("exif_inspector_panel"),
        color = PanelSurface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header with format badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EXIF & METADATA",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                if (photo != null) {
                    val badgeColor = when (photo.format) {
                        PhotoFormat.ARW -> FormatOrange // Sony Orange
                        PhotoFormat.DNG -> FormatCyan // Adobe Cyan
                        else -> FormatPurple
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = photo.format.displayName,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (photo == null) {
                Text(
                    text = "No photo selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            } else {
                // File Name & Size
                Text(
                    text = photo.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${photo.formattedFileSize}  •  ${photo.width} × ${photo.height} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = HairlineWhite)
                Spacer(modifier = Modifier.height(12.dp))

                // Camera & Lens
                MetadataRow(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    value = photo.cameraModel ?: "Unknown Body"
                )

                if (!photo.lensModel.isNullOrBlank()) {
                    MetadataRow(
                        icon = Icons.Default.Lens,
                        label = "Lens",
                        value = photo.lensModel
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Exposure Pills Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExposurePill(
                        label = "Shutter",
                        value = photo.shutterSpeed ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    ExposurePill(
                        label = "Aperture",
                        value = photo.aperture ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    ExposurePill(
                        label = "ISO",
                        value = photo.iso ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    ExposurePill(
                        label = "Focal",
                        value = photo.focalLength ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Date Time
                val dateStr = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.US).format(Date(photo.dateTaken))
                MetadataRow(
                    icon = Icons.Default.Schedule,
                    label = "Captured",
                    value = dateStr
                )

                if (!photo.exposureBias.isNullOrBlank()) {
                    MetadataRow(
                        icon = Icons.Default.Info,
                        label = "Exp Bias",
                        value = photo.exposureBias
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = HairlineWhite)
                Spacer(modifier = Modifier.height(10.dp))

                // Paired JPEG & Live XMP
                if (photo.hasCompanion) {
                    MetadataRow(
                        icon = Icons.Default.Info,
                        label = "Paired ${photo.companionFormat?.displayName ?: "file"}",
                        value = photo.companionFileName ?: "Yes"
                    )
                }

                MetadataRow(
                    icon = Icons.Default.Info,
                    label = "XMP Sidecar",
                    value = "${photo.sidecarFileName} (${photo.xmpSyncStatus.label})"
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = TextMuted
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextValue,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ExposurePill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(InsetSurface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryCyan
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = TextMuted
            )
        }
    }
}
