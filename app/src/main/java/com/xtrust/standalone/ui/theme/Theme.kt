package com.xtrust.standalone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val XtrustLightColors = lightColorScheme(
    primary = AccentPrimary,
    onPrimary = AccentOnPrimary,
    primaryContainer = SurfaceSubtle,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = AccentOnPrimary,
    secondaryContainer = SurfaceSubtle,
    onSecondaryContainer = TextPrimary,
    tertiary = StatusInfo,
    onTertiary = AccentOnPrimary,
    background = SurfaceBackground,
    onBackground = TextPrimary,
    surface = SurfaceBackground,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = SurfaceBackground,
    surfaceContainerLow = SurfaceMuted,
    surfaceContainer = SurfaceSubtle,
    surfaceContainerHigh = SurfaceHover,
    surfaceContainerHighest = SurfaceSelected,
    outline = DividerStrong,
    outlineVariant = DividerSubtle,
    error = StatusError,
    onError = AccentOnPrimary
)

@Composable
fun XtrustTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = XtrustLightColors,
        typography = Typography,
        content = content
    )
}
