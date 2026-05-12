package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.theme.MytharaTheme
import com.mythara.ui.theme.MytharaColors

/**
 * The single Compose root. Owns the theme and routes to the active screen.
 *
 * M0/M1 routes through ChatScreen directly. Later milestones (M4 onboarding,
 * M2 settings, M8 secret) layer in a NavHost.
 */
@Composable
fun MytharaRoot() {
    MytharaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg)
        ) {
            ChatScreen()
        }
    }
}
