package com.mythara.ui.system

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iPhone Dynamic-Island-style pill that lives in the centre of the
 * Mythara status bar.
 *
 * Two faces:
 *   - **Idle** (no active insight in [DynamicIslandSink]): renders
 *     the small rose mark + "MYTHARA" wordmark in lavender.
 *   - **Insight** (sink has an active item): renders a coloured
 *     dot + the insight's text. Pill widens via animateContentSize
 *     to fit longer text, snaps back when the insight expires.
 *
 * Animation policy (per user spec): the rose itself does NOT
 * continuously rotate or breathe inside the pill — that's the
 * popup amulet's + wallpaper's job. The pill is meant to be
 * QUIET in idle. The rose only animates on TAP — a one-shot
 * 360° spin + brief scale pulse — which also clears any pending
 * insight (interpreted as "I've seen it").
 *
 * Sized so a Pixel 10 Pro pinhole sits cleanly in the area
 * occupied by the strip's natural top padding ABOVE the pill;
 * the pill itself doesn't need to wrap the cutout.
 */
@Composable
fun DynamicIsland(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(initialValue = 0f) }
    val pulseScale = remember { Animatable(initialValue = 1f) }

    // Re-poll the sink twice per second. Cheap (single volatile
    // ref read + occasional list pop) and gets within ~500 ms of
    // any insight push — fast enough for a status-bar surface.
    val insight by produceState<DynamicIslandSink.Insight?>(initialValue = null, key1 = Unit) {
        while (true) {
            value = DynamicIslandSink.current()
            delay(POLL_INTERVAL_MS)
        }
    }

    val showingInsight by derivedStateOf { insight != null }

    Row(
        modifier = modifier
            .height(PILL_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(PILL_HEIGHT_DP.dp))
            .background(PILL_BG)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // Tap triggers a one-shot animation + clears
                    // the queue (acknowledgement). Even when idle,
                    // tapping still spins the rose — gives the
                    // user a small interactive flourish without
                    // requiring an active insight.
                    DynamicIslandSink.clear()
                    scope.launch {
                        rotation.snapTo(0f)
                        rotation.animateTo(
                            targetValue = 360f,
                            animationSpec = tween(durationMillis = 700, easing = LinearOutSlowInEasing),
                        )
                        rotation.snapTo(0f)
                    }
                    scope.launch {
                        pulseScale.snapTo(1f)
                        pulseScale.animateTo(1.12f, tween(durationMillis = 140))
                        pulseScale.animateTo(1f, tween(durationMillis = 220))
                    }
                },
            )
            .animateContentSize(animationSpec = tween(durationMillis = 220))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.scale(pulseScale.value),
        ) {
            RoseMarkSpinning(
                sizeDp = ROSE_DP,
                rotationDeg = rotation.value,
                accent = insight?.accent,
            )
        }
        if (showingInsight) {
            // Tiny coloured leading dot, mirrors iOS pill style.
            Box(
                modifier = Modifier
                    .size(ACCENT_DOT_DP.dp)
                    .clip(CircleShape)
                    .background(insight?.accent ?: MytharaColors.Charple),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = insight?.text.orEmpty().take(MAX_INSIGHT_CHARS),
                color = insight?.accent ?: MytharaColors.Fg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        } else {
            Text(
                text = "MYTHARA",
                color = RoseGeometry.Lavender,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

/** Tiny rose with optional rotation + accent override for tap-flash. */
@Composable
private fun RoseMarkSpinning(
    sizeDp: Int,
    rotationDeg: Float,
    accent: Color?,
) {
    val petalPath = remember { Path() }
    val hexPath = remember { Path() }
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = (minOf(size.width, size.height) * 0.5f) /
            RoseGeometry.OuterRadiusSourceUnits
        rotate(degrees = rotationDeg, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            for (deg in RoseGeometry.BigPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.BigPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = accent ?: RoseGeometry.Purple)
            }
            for (deg in RoseGeometry.SmallPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.SmallPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = RoseGeometry.Lavender)
            }
            RoseGeometry.hexPath(cx, cy, scale, hexPath)
            drawPath(hexPath, color = RoseGeometry.Cyan)
        }
    }
}

private const val PILL_HEIGHT_DP = 22
private const val ROSE_DP = 14
private const val ACCENT_DOT_DP = 6
private const val MAX_INSIGHT_CHARS = 28
private const val POLL_INTERVAL_MS = 500L

/** Same near-black as the iPhone Dynamic Island so the pill reads
 *  visually as "system chrome floating above the strip" rather than
 *  a flat-coloured chip. Slightly transparent so the underlying
 *  status bar bg shows through faintly when the pill is wide. */
private val PILL_BG = Color(0xCC000000)
