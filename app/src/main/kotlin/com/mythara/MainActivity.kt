package com.mythara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mythara.ui.MytharaRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Compose activity. Routing lives entirely inside [MytharaRoot] —
 * we deliberately don't use multiple Activities. Splash, Onboarding, Chat,
 * Settings, About, and the Secret unlock all share one window, one
 * Material 3 theme, and one navigation graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // System SplashScreen API — bridges from XML splash theme to the
        // Compose hero (MYTHARA wordmark) without a black flash.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MytharaRoot() }
    }
}
