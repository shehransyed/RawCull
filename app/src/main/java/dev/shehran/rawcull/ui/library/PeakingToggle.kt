package dev.shehran.rawcull.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import dev.shehran.rawcull.ui.theme.PrimaryCyan

/** The loupe toolbar's focus peaking switch (R6 D7); O does the same. */
@Composable
fun PeakingToggle(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconToggleButton(checked = on, onCheckedChange = { onClick() }, modifier = modifier.testTag("loupe_peaking_button")) {
        Icon(
            imageVector = if (on) Icons.Default.CenterFocusStrong else Icons.Default.CenterFocusWeak,
            contentDescription = "Focus peaking",
            tint = if (on) PrimaryCyan else Color.White,
        )
    }
}
