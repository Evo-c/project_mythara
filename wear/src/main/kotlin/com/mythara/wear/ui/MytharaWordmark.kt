package com.mythara.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/**
 * Stylised "M.Y.T.H.A.R.A" wordmark for the wear home.
 *
 * Per-letter colouring via AnnotatedString:
 *   • Letters M Y T H A R A — bold purple in monospace, with a
 *     purple shadow GLOW that bleeds softly outward (radius 14 px).
 *   • Separators .         — neon cyan (the exact same hex as the
 *     geometric rose's hexagon nucleus, [ROSE_CYAN]), with a SUBTLE
 *     cyan glow of their own — NOT the purple shadow, which would
 *     bleed onto them and shift the perceived hue toward lavender.
 *
 * The two glows are applied via per-span shadow on [SpanStyle] rather
 * than a single TextStyle-level shadow, so the purple aura around
 * the letters never touches the cyan of the dots — they stay the
 * unblemished `#68FFD6` of the rose centre.
 */
@Composable
fun MytharaWordmark(
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
) {
    val letterStyle = SpanStyle(
        color = PURPLE,
        fontWeight = FontWeight.ExtraBold,
        shadow = Shadow(
            color = PURPLE.copy(alpha = 0.85f),
            offset = Offset.Zero,
            blurRadius = 14f,
        ),
    )
    val dotStyle = SpanStyle(
        color = ROSE_CYAN,
        fontWeight = FontWeight.Bold,
        shadow = Shadow(
            color = ROSE_CYAN.copy(alpha = 0.55f),
            offset = Offset.Zero,
            blurRadius = 8f,
        ),
    )

    val styledText = buildAnnotatedString {
        val letters = "MYTHARA".toCharArray()
        for ((index, letter) in letters.withIndex()) {
            withStyle(letterStyle) { append(letter.toString()) }
            if (index < letters.lastIndex) {
                withStyle(dotStyle) { append(".") }
            }
        }
    }
    Text(
        text = styledText,
        modifier = modifier,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = fontSize,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            // No shadow at the TextStyle level — per-span shadows on
            // letterStyle/dotStyle keep purple glow off the cyan dots.
        ),
    )
}

private val PURPLE = Color(0xFF6B50FF)

/** The exact cyan/teal of the geometric rose's hexagon nucleus. Kept
 *  as a separate constant so every consumer (this wordmark, the
 *  watch-face colon, any future Mythara surface) refers to one
 *  source-of-truth value. */
private val ROSE_CYAN = Color(0xFF68FFD6)
