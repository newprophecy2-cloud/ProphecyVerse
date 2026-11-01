package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = InstitutionalGreenLight,
    onPrimary = InstitutionalGreenDark,
    primaryContainer = Color(0xFF00522B),
    onPrimaryContainer = Color(0xFF94F5B7),
    secondary = Color(0xFFB6CCB9),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = AdminSecondaryContainer,
    tertiary = Color(0xFFA3CDDA),
    onTertiary = Color(0xFF023640),
    background = AdminDarkBg,
    onBackground = AdminDarkOnSurface,
    surface = AdminDarkSurface,
    onSurface = AdminDarkOnSurface,
    surfaceVariant = AdminDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC0C9C0),
    outline = AdminDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = InstitutionalGreen,
    onPrimary = Color.White,
    primaryContainer = InstitutionalGreenContainer,
    onPrimaryContainer = OnInstitutionalGreenContainer,
    secondary = AdminSecondary,
    onSecondary = Color.White,
    secondaryContainer = AdminSecondaryContainer,
    onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = AdminTertiary,
    onTertiary = Color.White,
    background = AdminLightBg,
    onBackground = AdminLightOnSurface,
    surface = AdminLightSurface,
    onSurface = AdminLightOnSurface,
    surfaceVariant = AdminLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF404943),
    outline = AdminLightOutline
)

@Composable
fun ProphecyVerseAdminTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep consistent institutional green identity
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Keep backward-compatible alias
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = ProphecyVerseAdminTheme(darkTheme, dynamicColor, content)
