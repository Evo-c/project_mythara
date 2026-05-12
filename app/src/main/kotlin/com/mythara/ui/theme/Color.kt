package com.mythara.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Charmtone Pantera — the Crush palette, source-of-truth.
 *
 * Single semantic group; Material 3's [androidx.compose.material3.ColorScheme]
 * is built from these in [Theme.kt]. The named constants (Charple / Bok / …)
 * stay deliberately verbose so colour decisions are reviewable in PRs without
 * cross-referencing Material role aliases.
 */
object MytharaColors {
    // Surfaces — dark, layered terminal feel
    val Bg            = Color(0xFF201F26)   // app background
    val Surface       = Color(0xFF2D2C35)   // cards, composer
    val SurfaceMid    = Color(0xFF3A3943)   // bubbles, divider hairline
    val SurfaceHigh   = Color(0xFF4D4C57)   // raised panels, tool-call border

    // Foreground / text
    val Fg            = Color(0xFFDFDBDD)   // primary
    val FgMute        = Color(0xFFA8A4AB)   // body de-emphasis
    val FgDim         = Color(0xFF605F6B)   // timestamps, metadata

    // Brand — the violet→mint signature that screams Crush
    val Charple       = Color(0xFF6B50FF)   // primary brand
    val Bok           = Color(0xFF68FFD6)   // accent (active mic, success accent)

    // Semantic — borrowed straight from Crush themes.go
    val Sriracha      = Color(0xFFEB4268)   // error
    val Mustard       = Color(0xFFF5EF34)   // warning
    val Citron        = Color(0xFFE8FF27)   // busy / thinking
    val Malibu        = Color(0xFF00A4FF)   // info
    val Julep         = Color(0xFF00FFB2)   // success
}
