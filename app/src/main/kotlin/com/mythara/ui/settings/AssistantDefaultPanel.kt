package com.mythara.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Settings panel that explains the "Pixel Buds tap → Lumi" path and
 * deep-links to the system surface where the user makes Mythara the
 * default Digital Assistant app.
 *
 * Why a separate panel rather than rolled into the api-key block: the
 * default-assistant choice is a system-level handoff (Settings → Apps →
 * Default apps → Digital assistant app), not an in-app preference, and
 * the deep-link is the only thing we can offer.
 *
 * Once Mythara is the default assistant, any "open the assistant"
 * gesture (Pixel Buds touch-and-hold, hardware assist button, system
 * assist gesture / corner-swipe) delivers MainActivity an
 * `ACTION_ASSIST` intent. [com.mythara.voice.VoiceActionStore] picks
 * that up and [com.mythara.ui.chat.ChatScreen] starts a one-shot
 * SpeechRecognition listen that submits to the agent.
 */
@Composable
fun AssistantDefaultPanel() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} pixel buds & default assistant",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Text(
            text = "tap-and-hold your Pixel Buds (or hit the system assist gesture) and Lumi listens immediately — but only if Mythara is set as your default Digital Assistant app.",
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { openDigitalAssistantSettings(ctx) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text("${Glyph.Arrow} open default-assistant settings")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} the page that opens lets you pick which app handles 'open the digital assistant'. Tap Mythara, accept the consent, come back. Once set, every Pixel Buds long-press or corner-swipe will pop Mythara open with the mic already listening — speak, and Lumi answers via your earbuds.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun openDigitalAssistantSettings(ctx: Context) {
    // ACTION_VOICE_INPUT_SETTINGS lands on the "Choose assistant app"
    // surface on most Android 11+ devices. On a few OEM skins it
    // lands one level up at "Voice and input"; the user takes one
    // more tap from there. If even that fails (very old shells we
    // don't target) we fall back to the catch-all top-level Settings.
    val targets = listOf(
        Intent("android.settings.VOICE_INPUT_SETTINGS"),
        Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in targets) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return
    }
}
