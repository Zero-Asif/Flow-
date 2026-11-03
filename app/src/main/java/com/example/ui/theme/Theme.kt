package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DeezerPurplePrimary,
    onPrimary = Color.White,
    primaryContainer = DeezerPurpleDark,
    onPrimaryContainer = Color.White,
    secondary = DeezerPinkAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF831843),
    onSecondaryContainer = Color(0xFFFFD1DC),
    tertiary = CyberCyan,
    onTertiary = Color.Black,
    background = SurfaceDarkVoid,
    onBackground = TextPrimaryDark,
    surface = SurfaceDarkElevated,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceDarkCard,
    onSurfaceVariant = TextSecondaryDark,
    outline = SurfaceDarkBorder
)

private val LightColorScheme = darkColorScheme(
    primary = DeezerPurplePrimary,
    onPrimary = Color.White,
    secondary = DeezerPinkAccent,
    onSecondary = Color.White,
    background = SurfaceDarkVoid,
    surface = SurfaceDarkElevated
)

@Composable
fun FlowMusicTheme(
    darkTheme: Boolean = true, // Audiophile music apps look best in dark aesthetic
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
