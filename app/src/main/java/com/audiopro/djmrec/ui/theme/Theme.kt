package com.audiopro.djmrec.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DjmRecColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = BackgroundDark,
    secondary = AccentAmber,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    error = AccentRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

/** A pro-audio, permanently-dark theme — matches the visual language of hardware DJ gear. */
@Composable
fun DjmRecTheme(content: @Composable () -> Unit) {
    // Intentionally ignore system light/dark mode: a VU meter and transport UI needs a
    // consistent, low-glare dark surface regardless of device theme, same as any DAW.
    MaterialTheme(
        colorScheme = DjmRecColorScheme,
        typography = DjmRecTypography,
        content = content
    )
}
