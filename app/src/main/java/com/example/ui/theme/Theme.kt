package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentIndigo,
    onPrimary = Color.White,
    primaryContainer = SurfaceHighlight,
    onPrimaryContainer = AccentCyan,
    secondary = AccentCyan,
    onSecondary = SpaceDark,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = AccentCyan,
    tertiary = AccentPurple,
    onTertiary = Color.White,
    background = SpaceDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = SurfaceHighlight,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun ProphecyVerseTheme(
    darkTheme: Boolean = true, // Anime app is dark-first
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
