package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark

/**
 * M0/M1 chat scaffold. Renders the hero (wordmark + tagline + push-to-talk
 * placeholder) and an empty transcript area. Streaming chat, history, the
 * push-to-talk action, and the runtime selector are wired in M2+M3.
 *
 * Layout is three rows: header (titlebar height), transcript (flexible),
 * composer (bottom-anchored with mic ring).
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(insets),
    ) {
        ChatHeader()
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            EmptyStateHero()
        }
        Composer()
    }
}

@Composable
private fun ChatHeader() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${Glyph.DiamondFilled} mythara",
            style = MaterialTheme.typography.labelLarge.copy(
                color = MytharaColors.Charple, fontWeight = FontWeight.Bold,
            ),
        )
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MytharaColors.Surface)
                .border(1.dp, MytharaColors.SurfaceHigh, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} Assistive",
                style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.FgMute),
            )
        }
    }
}

@Composable
private fun EmptyStateHero() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MytharaWordmark(shimmer = true, fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${Glyph.AccentBar} field intelligence in your pocket.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MytharaColors.FgDim, letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "hold the mic to talk ${Glyph.Arrow}",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgMute),
        )
    }
}

@Composable
private fun Composer() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MytharaColors.Surface)
                .border(2.dp, MytharaColors.Charple, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = Glyph.CircleFilled,
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
