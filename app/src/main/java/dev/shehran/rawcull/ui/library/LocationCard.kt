package dev.shehran.rawcull.ui.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.GeoPoint
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.location
import dev.shehran.rawcull.data.scan.CameraLocations
import dev.shehran.rawcull.data.scan.samePlaceAs
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.library.GeoInput
import dev.shehran.rawcull.library.GeoParse
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.ui.theme.TextValue

/** Reads a photo's own EXIF location, to tell the camera's apart from the sidecar's. */
val LocalCameraLocations = staticCompositionLocalOf { CameraLocations.None }

/** Where a photo's shown location comes from (R7 D13). */
enum class LocationSource(val label: String) { NONE("No location"), SIDECAR("Sidecar"), CAMERA("From the camera") }

/**
 * The inspector's Location card: the value, where it comes from, and what can be done with it.
 * Edits go through [onEdit], so they land on the selection like every other edit and can be undone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationCard(
    photo: PhotoAsset?,
    selectedCount: Int,
    onEdit: (CullEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cameras = LocalCameraLocations.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val location = photo?.location
    val camera by produceState<GeoPoint?>(null, photo?.id, photo?.gpsInFile, photo?.fileUri) {
        value = if (photo != null && photo.gpsInFile) cameras.of(photo) else null
    }
    val source = when {
        location == null -> LocationSource.NONE
        photo?.gpsInFile == true && camera?.samePlaceAs(location) == true -> LocationSource.CAMERA
        else -> LocationSource.SIDECAR
    }
    val targets = if (selectedCount > 1) "$selectedCount selected photos" else null
    val startEditing = {
        draft = location?.let(GeoInput::plain).orEmpty()
        editing = true
    }

    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).testTag("location_card"),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "LOCATION",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            if (location == null) {
                Text("No location", color = TextMuted, fontSize = 12.sp, modifier = Modifier.testTag("location_value"))
            } else {
                Text(
                    GeoInput.format(location) + (GeoInput.formatAltitude(location)?.let { ", $it" } ?: ""),
                    color = TextValue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("location_value"),
                )
                Text(source.label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.testTag("location_source"))
            }
            if (targets != null) Text("Edits apply to the $targets", color = TextMuted, fontSize = 11.sp)
            Button(
                onClick = startEditing,
                enabled = photo != null,
                modifier = Modifier.padding(top = 4.dp).testTag(if (location == null) "location_add" else "location_edit"),
            ) {
                Icon(if (location == null) Icons.Default.AddLocationAlt else Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(locationActionLabel(location != null, selectedCount), modifier = Modifier.padding(start = 8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (location != null) {
                    CardButton(Icons.Default.ContentCopy, "Copy", enabled = true, tag = "location_copy") { copy(context, location) }
                }
                CardButton(Icons.Default.ContentPaste, "Paste", enabled = photo != null, tag = "location_paste") {
                    val text = clipboardText(context)
                    when (val parsed = GeoInput.parse(text)) {
                        is GeoParse.Ok -> onEdit(CullEdit.SetLocation(parsed.point))
                        // Not a place: let the person see why, and fix it by hand.
                        is GeoParse.Invalid -> {
                            draft = text
                            editing = true
                        }
                    }
                }
                if (location != null) {
                    CardButton(Icons.Default.Map, "Open in Maps", enabled = true, tag = "location_maps") { openInMaps(context, location) }
                    // Only the sidecar's can go; a camera location is in the file (R7 D13).
                    CardButton(
                        Icons.Default.LocationOff,
                        "Clear",
                        enabled = source == LocationSource.SIDECAR,
                        tag = "location_clear",
                    ) { onEdit(CullEdit.ClearLocation) }
                }
            }
        }
    }
    if (editing) {
        LocationEditSheet(
            initial = draft,
            targetCount = maxOf(selectedCount, 1),
            onApply = { point ->
                editing = false
                onEdit(CullEdit.SetLocation(point))
            },
            onDismiss = { editing = false },
        )
    }
}

/** The card's main button: add when there's no location, edit when there is (R8 D6). */
internal fun locationActionLabel(hasLocation: Boolean, selectedCount: Int): String = when {
    selectedCount > 1 && hasLocation -> "Edit location of $selectedCount photos"
    selectedCount > 1 -> "Add location to $selectedCount photos"
    hasLocation -> "Edit"
    else -> "Add location"
}

@Composable
private fun CardButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = if (enabled) PrimaryCyan else TextMuted, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 11.sp, color = if (enabled) PrimaryCyan else TextMuted, modifier = Modifier.padding(start = 4.dp))
    }
}

/** One text field: the point it parses to shows live, and *Apply* waits for a point (R7 D14). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationEditSheet(
    initial: String,
    targetCount: Int,
    onApply: (GeoPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    val parsed = remember(text.text) { GeoInput.parse(text.text) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        // Inside the sheet, which is its own window: from outside, the field isn't there yet (R7.9).
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .dismissOnEscape(takeFocus = false, onDismiss = onDismiss)
                .testTag("location_edit_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (targetCount == 1) "Set the location of 1 photo" else "Set the location of $targetCount photos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Coordinates or a map link") },
                supportingText = { Text("40.4461, -79.9822 · 40°26'46\"N 79°58'56\"W · a Google, Apple or OpenStreetMap link · add , 120 m for altitude") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("location_input"),
            )
            when (val result = parsed) {
                is GeoParse.Ok -> Text(
                    GeoInput.format(result.point) + (GeoInput.formatAltitude(result.point)?.let { ", $it" } ?: ""),
                    color = TextValue,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("location_preview"),
                )
                is GeoParse.Invalid -> if (text.text.isNotBlank()) {
                    Text(result.reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("location_error"))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { (parsed as? GeoParse.Ok)?.let { onApply(it.point) } },
                    enabled = parsed is GeoParse.Ok,
                    modifier = Modifier.testTag("location_apply"),
                ) { Text("Apply") }
            }
        }
    }
}

private fun clipboard(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

private fun copy(context: Context, point: GeoPoint) {
    clipboard(context).setPrimaryClip(ClipData.newPlainText("Location", GeoInput.plain(point)))
}

private fun clipboardText(context: Context): String =
    clipboard(context).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

private fun openInMaps(context: Context, point: GeoPoint) {
    val where = "${point.latitude},${point.longitude}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$where?q=$where")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show()
    }
}
