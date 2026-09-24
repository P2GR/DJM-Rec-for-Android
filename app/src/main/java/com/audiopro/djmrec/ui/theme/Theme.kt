package com.audiopro.djmrec.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DjmRecColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = BackgroundDark,
    secondary = AccentAmber,
    onSecondary = BackgroundDark,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = OutlineSubtle,
    error = AccentRed,
    onError = BackgroundDark
)

/** Generous, consistent radii: tiles 10dp, fields/sheets 16dp, cards 20dp, big sheets 28dp. */
private val DjmRecShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** A pro-audio, permanently-dark theme — matches the visual language of hardware DJ gear. */
@Composable
fun DjmRecTheme(content: @Composable () -> Unit) {
    // Intentionally ignore system light/dark mode: a VU meter and transport UI needs a
    // consistent, low-glare dark surface regardless of device theme, same as any DAW.
    MaterialTheme(
        colorScheme = DjmRecColorScheme,
        typography = DjmRecTypography,
        shapes = DjmRecShapes,
        content = content
    )
}

/**
 * Android's reduced-motion signal: the user disabled animations (animator duration scale = 0)
 * in Accessibility settings. Decorative motion (blinks, pulses) must respect this; functional
 * data animation (waveform scroll, VU smoothing) may continue.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}
