package com.mythara.ui.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mythara's own status strip rendered at the top of every screen
 * since the system status bar is hidden in launcher mode (see
 * MainActivity's WindowInsetsController.hide call).
 *
 * Renders the three pieces a user actually scans the system bar
 * for: clock, battery percent, charging indicator. Network signal
 * is intentionally NOT included — it'd require a phone-state
 * permission that the typical user never granted, and the live
 * wallpaper / amulet's HR pulse already gives ambient "system is
 * alive" signal.
 *
 * The strip respects the device's status-bar inset height so it
 * doesn't clip the camera notch / hole-punch on devices that have
 * one — the height comes from WindowInsets.statusBars below the
 * Box height.
 */
@Composable
fun MytharaStatusBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    // Live clock — re-evaluates every 30s. Same cadence the system
    // clock would tick at; finer doesn't help and burns battery.
    val nowFmt by produceState(initialValue = formatNow(), key1 = Unit) {
        while (true) {
            value = formatNow()
            delay(30_000L)
        }
    }

    // Battery state — from a sticky broadcast (no permission needed,
    // ACTION_BATTERY_CHANGED is freely subscribable). Refreshes on
    // every state change the system pushes (~ once per minute when
    // discharging, more often when plugged).
    var battery by remember { mutableStateOf(readBattery(ctx)) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                battery = readBattery(ctx, intent)
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // Strip itself. Height roughly matches a system status bar so
    // existing screen layouts (which currently account for system
    // bars via WindowInsets.systemBars) continue to look right —
    // the strip just replaces the system pixels.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT_DP.dp)
            .background(MytharaColors.Bg)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        // Left: the clock. Anchors to the start so users always
        // glance to the same spot — same convention as the system
        // bar in 24-hour locales.
        Text(
            text = nowFmt,
            color = MytharaColors.Fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        // Right: battery percent + a tiny battery glyph that fills
        // proportionally to the level.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (battery.charging) {
                Text(
                    text = "⚡",
                    color = MytharaColors.Mustard,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "${battery.percent}%",
                color = MytharaColors.FgMute,
                fontSize = 11.sp,
            )
            BatteryGlyph(percent = battery.percent, charging = battery.charging)
        }
    }
}

@Composable
private fun BatteryGlyph(percent: Int, charging: Boolean) {
    val accent = when {
        percent <= 15 -> MytharaColors.Charple
        charging -> MytharaColors.Bok
        else -> MytharaColors.FgMute
    }
    Box(
        modifier = Modifier
            .size(width = 22.dp, height = 11.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MytharaColors.SurfaceHigh)
            .padding(1.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent / 100f)
                .height(9.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent),
        )
    }
    // Tiny terminal nub on the right of the body — visual cue
    // that this is a battery glyph and not just a generic bar.
    Spacer(Modifier.width(1.dp))
    Box(
        modifier = Modifier
            .size(width = 2.dp, height = 5.dp)
            .background(MytharaColors.SurfaceHigh),
    )
}

/** Strip height in dp — picked to roughly match a Pixel system
 *  status bar (24-26dp) so no existing screen layout has to shift. */
internal const val STRIP_HEIGHT_DP = 24

private fun formatNow(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private data class BatterySnapshot(val percent: Int, val charging: Boolean)

private fun readBattery(ctx: Context, intent: Intent? = null): BatterySnapshot {
    val sticky = intent ?: ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val charging = plugged != 0 ||
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatterySnapshot(percent = pct, charging = charging)
}
