package com.omninode.hub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Material 3 Dark Color Scheme — OmniNode "Neon Edge" ──────────────────────
private val OmniNodeDarkColorScheme = darkColorScheme(
    primary            = Primary,
    onPrimary          = OnPrimary,
    primaryContainer   = PrimaryDim,
    onPrimaryContainer = Primary,

    secondary          = Secondary,
    onSecondary        = OnSecondary,
    secondaryContainer = SecondaryDim,
    onSecondaryContainer = Secondary,

    tertiary           = Tertiary,
    onTertiary         = OnTertiary,
    tertiaryContainer  = TertiaryDim,
    onTertiaryContainer = Tertiary,

    background         = Background,
    onBackground       = TextPrimary,

    surface            = BackgroundVariant,
    onSurface          = TextPrimary,
    surfaceVariant     = SurfaceGlass,
    onSurfaceVariant   = TextSecondary,

    error              = AlertRed,
    onError            = Color.White,

    outline            = Color(0xFF2A3550),
    outlineVariant     = Color(0xFF1A2035),
)

/**
 * OmniNodeTheme — The root Material 3 theme wrapper.
 *
 * Always dark — the "Neon Edge" dark theme is the primary OmniNode aesthetic.
 * Light mode is intentionally not supported (smart home dashboards are
 * predominantly used in dim environments).
 */
@Composable
fun OmniNodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmniNodeDarkColorScheme,
        typography  = OmniNodeTypography,
        content     = content,
    )
}
