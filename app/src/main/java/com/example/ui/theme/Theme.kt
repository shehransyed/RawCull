package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkroomColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = Color.Black,
    primaryContainer = PrimaryCyanVariant,
    onPrimaryContainer = Color.White,
    secondary = EmeraldPick,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
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
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkroomColorScheme,
        typography = Typography,
        content = content
    )
}
