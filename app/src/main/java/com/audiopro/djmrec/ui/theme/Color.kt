package com.audiopro.djmrec.ui.theme

import androidx.compose.ui.graphics.Color

// ── Palette ──────────────────────────────────────────────────────────────────────
// Brand palette: every functional pair passes WCAG AA on dark surfaces (measured in
// UX-UI-AUDIT.md). Consume the SEMANTIC roles below or MaterialTheme.colorScheme,
// not these raw values, so meaning never drifts between screens.

val BackgroundDark = Color(0xFF101416)
val SurfaceDark = Color(0xFF1B2125)
val SurfaceVariantDark = Color(0xFF293238)
val TextPrimary = Color(0xFFF5F6FA)
val TextSecondary = Color(0xFF9AA1B2)

// Mint = armed / ready / connected. Amber = warning / attention.
// Red = on air & recording (and destructive). Keep the three roles separate.
val AccentGreen = Color(0xFF00E5A0)
val AccentAmber = Color(0xFFFFC93C)
val AccentRed = Color(0xFFFF4D4D)

/** On-air / recording status color. */
val StatusLive = AccentRed

/** Hairlines, tile borders and dividers (white at 25%). */
val OutlineSubtle = Color(0x40F5F6FA)

// Waveform spectrum colors (data visualization only, not general accents).
val WaveformLow = Color(0xFFFF315E)
val WaveformMid = Color(0xFF29F19C)
val WaveformHigh = Color(0xFF25A7FF)

// CDJ-3000 "3Band" waveform layers: blue body (low) -> amber (mid) -> white (high).
val WaveformCdjLow = Color(0xFF2E6BFF)
val WaveformCdjMid = Color(0xFFF0A93C)
val WaveformCdjHi = Color(0xFFFFFFFF)

// VU meter gradient stops (green -> amber -> red as level approaches 0 dBFS).
val MeterGreen = AccentGreen
val MeterAmber = AccentAmber
val MeterRed = AccentRed
