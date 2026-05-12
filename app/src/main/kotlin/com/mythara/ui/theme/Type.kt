package com.mythara.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mythara.R

/**
 * JetBrains Mono everywhere — terminal-aesthetic by intent. The font files
 * live at `res/font/jetbrains_mono_regular.ttf` / `_bold.ttf` and are
 * downloaded during build setup (see project README).
 *
 * If the .ttf is missing we fall back to the system monospace family so the
 * app still builds; the look degrades to "generic mono" but stays readable.
 */
val JetBrainsMono: FontFamily = try {
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
} catch (_: Throwable) {
    FontFamily.Monospace
}

/**
 * Material 3 typography mapped to JetBrains Mono. Sizes are tuned for a
 * dense terminal-style chat — 13sp body, 11sp metadata — not the Material
 * defaults.
 */
val MytharaTypography: Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp,
    ),
)
