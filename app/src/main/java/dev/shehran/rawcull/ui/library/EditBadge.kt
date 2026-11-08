package dev.shehran.rawcull.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.library.EditBadgeText
import dev.shehran.rawcull.ui.theme.ScrimStrong
import kotlinx.coroutines.delay

/** How long a badge stays up after an edit. */
private const val BADGE_MS = 1000L

/**
 * Words for the latest flag, rating or label change of [photo], for about a second; null otherwise.
 * It keys on those three fields only, so a sidecar sync finishing doesn't cut the badge short.
 */
@Composable
fun rememberEditBadgeText(photo: PhotoAsset?): String? {
    var previous by remember { mutableStateOf(photo) }
    var text by remember { mutableStateOf<String?>(null) }
    val key = listOf(photo?.id, photo?.cullStatus, photo?.rating, photo?.colorLabel)
    LaunchedEffect(key) {
        val before = previous
        previous = photo
        val words = if (before != null && photo != null) EditBadgeText.between(before, photo) else null
        if (words != null) {
            text = words
            delay(BADGE_MS)
            text = null
        } else {
            text = null
        }
    }
    return text
}

/** A small pill that fades in with [text] and out when it goes null. */
@Composable
fun EditBadge(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text = text.orEmpty(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ScrimStrong)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("edit_badge"),
        )
    }
}
