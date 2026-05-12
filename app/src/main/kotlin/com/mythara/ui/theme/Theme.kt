package com.mythara.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The single MaterialTheme wrapper. Mythara is dark-only — Crush is dark-only
 * — so we don't expose a light variant. If we ever need one, branch on
 * `isSystemInDarkTheme()` here; for now, force dark always.
 *
 * Material 3 colour roles are mapped to the Charmtone palette. The role
 * mapping is deliberate (not just "primary = brand") so default Material
 * components render in a way that's coherent with the hand-styled
 * Crush surfaces (composer, tool-call bubble, wordmark).
 */
private val MytharaColorScheme = darkColorScheme(
    primary             = MytharaColors.Charple,
    onPrimary           = MytharaColors.Fg,
    secondary           = MytharaColors.Bok,
    onSecondary         = MytharaColors.Bg,
    tertiary            = MytharaColors.Malibu,
    background          = MytharaColors.Bg,
    onBackground        = MytharaColors.Fg,
    surface             = MytharaColors.Surface,
    onSurface           = MytharaColors.Fg,
    surfaceVariant      = MytharaColors.SurfaceMid,
    onSurfaceVariant    = MytharaColors.FgMute,
    outline             = MytharaColors.SurfaceHigh,
    outlineVariant      = MytharaColors.SurfaceMid,
    error               = MytharaColors.Sriracha,
    onError             = MytharaColors.Fg,
)

@Composable
fun MytharaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider {
        MaterialTheme(
            colorScheme = MytharaColorScheme,
            typography  = MytharaTypography,
            content     = content,
        )
    }
}
