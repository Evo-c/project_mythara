package com.mythara.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MYTHARA wordmark — the Crush violet→mint gradient on a chunky monospace.
 * This is *the* visual signature: same trick Crush uses on its "CRUSH"
 * banner in the TUI. Reused as a thinking-state shimmer by passing
 * `shimmer = true`, which animates the gradient offset horizontally.
 *
 * The animated brush is intentionally a [Brush.linearGradient] driven off
 * an [Offset], not a snapshot of HSL stops, so the sweep stays GPU-cheap
 * and the wordmark text re-measures only when font sizes change.
 */
@Composable
fun MytharaWordmark(
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 44.sp,
    text: String = "MYTHARA",
    shimmer: Boolean = false,
) {
    val sweep: Float = if (shimmer) {
        val infinite = rememberInfiniteTransition(label = "wordmarkSweep")
        val v by infinite.animateFloat(
            initialValue = 0f,
            targetValue  = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        v
    } else 0f

    // Tile width should be > text width so the gradient stretches across the
    // wordmark without repeating mid-character. 1200f is fine for up to ~12
    // chars at 44sp; bump if you ever use a longer brand string.
    val tile = 1200f
    val offsetX = -tile + sweep * (tile * 2)

    val brush = Brush.linearGradient(
        colors = listOf(MytharaColors.Charple, MytharaColors.Bok),
        start = Offset(offsetX, 0f),
        end   = Offset(offsetX + tile, 0f),
    )

    Box(modifier = modifier) {
        Text(
            text = text,
            style = LocalTextStyle.current.copy(
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize   = fontSize,
                brush      = brush,
                letterSpacing = 4.sp,
            ),
        )
    }
}

/**
 * Smaller wordmark used in the titlebar / about screen.
 */
@Composable
fun MytharaWordmarkInline(
    text: String = "MYTHARA",
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    modifier: Modifier = Modifier,
) {
    val brush = Brush.linearGradient(
        colors = listOf(MytharaColors.Charple, MytharaColors.Bok),
    )
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize   = fontSize,
            brush      = brush,
            letterSpacing = 2.sp,
        ),
    )
}
