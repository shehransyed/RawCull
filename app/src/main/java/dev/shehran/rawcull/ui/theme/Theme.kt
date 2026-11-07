package dev.shehran.rawcull.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkroomColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = Color.Black,
    primaryContainer = PrimaryCyanVariant,
    onPrimaryContainer = Color.White,
    secondary = EmeraldPick,
    onSecondary = Color.Black,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = PairedMint,
    tertiary = AmberRating,
    onTertiary = Color.Black,
    background = DarkroomBackground,
    onBackground = TextPrimary,
    surface = DarkroomSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkroomSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = RoseReject,
    onError = Color.White,
    outline = DarkroomBorder
)

@Composable
fun RawCullTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRawCullColors provides DarkroomColors) {
        MaterialTheme(
            colorScheme = DarkroomColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
