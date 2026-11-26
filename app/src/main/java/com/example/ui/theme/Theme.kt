package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ArcDarkColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = ArcVoid,
    primaryContainer = ArcSurfaceElevated,
    onPrimaryContainer = ArcCyanSoft,
    secondary = ArcIonBlue,
    onSecondary = ArcTextPrimary,
    secondaryContainer = ArcSurface,
    onSecondaryContainer = ArcTextPrimary,
    tertiary = ArcTeal,
    onTertiary = ArcVoid,
    background = ArcVoid,
    onBackground = ArcTextPrimary,
    surface = ArcSurface,
    onSurface = ArcTextPrimary,
    surfaceVariant = ArcSurfaceElevated,
    onSurfaceVariant = ArcTextSecondary,
    error = ArcAlertCrimson,
    onError = ArcTextPrimary,
    outline = ArcGlassBorder
)

@Composable
fun ArcTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ArcDarkColorScheme,
        typography = ArcTypography,
        content = content
    )
}
