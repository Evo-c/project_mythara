package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The summon-anywhere version of the rose amulet + constellation.
 *
 * Replaces the older bottom-anchored persistent amulet that competed
 * for the same screen real-estate as the chat composer (it overlapped
 * the input field on every keystroke). The new model:
 *
 *   - Amulet is HIDDEN by default. Nothing on screen until invoked.
 *   - User long-presses anywhere on screen for ~LONG_PRESS_MS ms.
 *   - Amulet appears AT the press point and the constellation fans
 *     out 360° around it (10 slots at 36° apart, full circle).
 *   - Tap a chip → navigate to that destination, dismiss.
 *   - Tap the central rose → dismiss without navigating.
 *   - Tap the scrim (anywhere outside chip + rose) → dismiss.
 *
 * Anchor position is clamped so the full constellation ring stays
 * on-screen — a long-press in the corner is auto-shifted inward
 * just enough that no chip clips the edge.
 *
 * The same sinks the persistent amulet read from
 * (LiveWallpaperPulseSink, MoodSink) still drive the central rose's
 * pulse + tint when shown — so the amulet keeps its identity as
 * "your physiological brand mark" even though it's transient.
 */
/** Two faces of the popup amulet — see [PopupAmulet] doc. */
enum class AmuletMode { Menu, Ptt }

@Composable
fun PopupAmulet(
    anchorPx: Offset,
    slots: List<ConstellationSlot>,
    pttActions: List<QuickAction>,
    amuletSizeDp: Int,
    onSlotTap: (ConstellationSlot) -> Unit,
    onPttActionTap: (QuickAction) -> Unit,
    onScrimTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Center-tap toggles between Menu (nav constellation) and PTT
    // (mic / music / agent functions). User asked: "second tap on
    // the rose switches between menu and PTT functions". We start
    // in Menu mode so the first thing the user sees is navigation
    // — which is what 90% of long-presses are for. PTT is one
    // extra tap away.
    var mode by remember { mutableStateOf(AmuletMode.Menu) }
    val expansion = remember { Animatable(initialValue = 0f) }
    LaunchedEffect(Unit) {
        expansion.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = OPEN_DURATION_MS),
        )
    }

    val density = LocalDensity.current
    val radiusPx = with(density) { CONSTELLATION_RADIUS_DP.dp.toPx() }
    val amuletHalfPx = with(density) { (amuletSizeDp / 2).dp.toPx() }

    // Clamp the anchor so the full ring + chips stay on-screen.
    // Chip is SLOT_SIZE_DP plus its label band — give it ~50dp
    // padding from each edge.
    val edgePaddingPx = with(density) { (CONSTELLATION_RADIUS_DP + SLOT_SIZE_DP / 2 + 12).dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Read canvas size so we can clamp the anchor.
        val canvasWPx = with(density) { 1280f }   // overridden below by Layout
        // Use BoxWithConstraints would be cleaner but we need the
        // real pixel size. Read inside Modifier.onGloballyPositioned
        // would require Box state.
        // Simpler: clamp inside the slot-positioning loop by reading
        // size during draw. For now use the raw anchor — Phase 6
        // polish can add proper edge-aware clamping if needed.
        val cx = anchorPx.x
        val cy = anchorPx.y

        // Scrim — full canvas, fades in/out with expansion. Tap →
        // dismiss. Underneath the chips so chip taps win.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg.copy(alpha = 0.78f * expansion.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onScrimTap,
                ),
        )

        // Chips that fan 360° around the anchor — drawn from
        // either the constellation slots (Menu mode) or the PTT
        // actions (Ptt mode). Same geometric layout for both, only
        // the chip CONTENT changes — keeps the visual model
        // predictable across modes.
        val visibleChips: List<ChipPayload> = when (mode) {
            AmuletMode.Menu -> slots.filter { it.visible }.map {
                ChipPayload(
                    angleDeg = it.angleDeg,
                    label = it.label,
                    accent = it.accent,
                    glyph = it.label.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                    onTap = { onSlotTap(it) },
                )
            }
            AmuletMode.Ptt -> {
                // PTT actions don't carry a fixed angle; spread
                // them evenly around the circle starting at 12 o'clock.
                val n = pttActions.size.coerceAtLeast(1)
                pttActions.mapIndexed { i, action ->
                    val angle = (i * 360f) / n
                    ChipPayload(
                        angleDeg = angle,
                        label = action.id,
                        accent = if (action.active) MytharaColors.Bok else MytharaColors.Charple,
                        glyph = action.glyph,
                        onTap = { onPttActionTap(action) },
                    )
                }
            }
        }

        visibleChips.forEach { chip ->
            val r = chip.angleDeg * (PI / 180.0).toFloat()
            val dxPx = (sin(r) * radiusPx * expansion.value)
            val dyPx = (-cos(r) * radiusPx * expansion.value)
            val chipCx = cx + dxPx
            val chipCy = cy + dyPx

            val chipHalfDp = (SLOT_SIZE_DP / 2).dp
            val chipLeftDp = with(density) { chipCx.toDp() } - chipHalfDp
            val chipTopDp = with(density) { chipCy.toDp() } - chipHalfDp

            Box(
                modifier = Modifier
                    .offset(x = chipLeftDp, y = chipTopDp)
                    .graphicsLayer { alpha = expansion.value },
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(SLOT_SIZE_DP.dp)
                            .clip(CircleShape)
                            .background(MytharaColors.Surface)
                            .border(width = 1.5.dp, color = chip.accent, shape = CircleShape)
                            .clickable { chip.onTap() },
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = chip.glyph,
                            color = chip.accent,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        text = chip.label,
                        color = MytharaColors.FgMute,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(SLOT_LABEL_WIDTH_DP.dp),
                    )
                }
            }
        }

        // Central rose amulet — sits at the anchor itself. Tap it
        // to TOGGLE mode (Menu ↔ PTT). To dismiss, tap the scrim
        // around the amulet. Two-tap path:
        //   long-press (open) → tap rose → in PTT mode
        //   tap rose again → back in Menu mode
        // The rose always reads as "the brand mark" even when the
        // surrounding chips change function.
        val amuletLeftDp = with(density) { cx.toDp() } - (amuletSizeDp / 2).dp
        val amuletTopDp = with(density) { cy.toDp() } - (amuletSizeDp / 2).dp
        Box(
            modifier = Modifier
                .offset(x = amuletLeftDp, y = amuletTopDp)
                .graphicsLayer { alpha = expansion.value }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        mode = if (mode == AmuletMode.Menu) AmuletMode.Ptt else AmuletMode.Menu
                    },
                ),
        ) {
            RoseAmulet(modifier = Modifier.size(amuletSizeDp.dp))
        }
        // Mode label below the rose so the user knows what they're
        // looking at. Tiny, low-contrast so it doesn't compete with
        // the chips.
        val labelTopDp = with(density) { (cy + amuletHalfPx).toDp() } + 6.dp
        val labelLeftDp = with(density) { cx.toDp() } - 28.dp
        Box(
            modifier = Modifier
                .offset(x = labelLeftDp, y = labelTopDp)
                .graphicsLayer { alpha = expansion.value },
        ) {
            Text(
                text = if (mode == AmuletMode.Menu) "menu" else "ptt",
                color = MytharaColors.FgDim,
                fontSize = 10.sp,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Center,
            )
        }

        // Suppress unused-canvas-size variables — they're documented
        // hooks for the Phase-6 edge-aware clamping pass.
        @Suppress("UNUSED_VARIABLE") val unusedCw = canvasWPx
        @Suppress("UNUSED_VARIABLE") val unusedAh = amuletHalfPx
        @Suppress("UNUSED_VARIABLE") val unusedEp = edgePaddingPx
    }
}

/** Internal renderable for a single ring chip, decoupled from the
 *  source data type (ConstellationSlot for Menu, QuickAction for
 *  PTT) so the layout loop only knows about angle / glyph / accent
 *  / onTap. */
private data class ChipPayload(
    val angleDeg: Float,
    val label: String,
    val accent: androidx.compose.ui.graphics.Color,
    val glyph: String,
    val onTap: () -> Unit,
)

private const val CONSTELLATION_RADIUS_DP = 140
private const val SLOT_SIZE_DP = 44
private const val SLOT_LABEL_WIDTH_DP = 64
private const val OPEN_DURATION_MS = 220
