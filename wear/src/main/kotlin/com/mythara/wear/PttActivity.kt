package com.mythara.wear

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.util.Locale

private val PURPLE = Color(0xFF6B50FF)
private val BOK = Color(0xFF68FFD6)

/**
 * Standalone push-to-talk entry point — a SEPARATE launchable activity
 * (its own MAIN/LAUNCHER intent filter) so it shows up as its own
 * target in the Galaxy Watch's "Customize keys" picker. Assign it to
 * the Action button and one press jumps straight into listening, ships
 * the transcript to the phone agent over the Data Layer, and closes.
 *
 * Deliberately minimal: no nav, no lists — open, listen, send, finish.
 * The full multi-screen app stays in [MainActivity].
 */
class PttActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pin to portrait in code as well as the manifest — Samsung's
        // One UI Watch doesn't reliably honour the manifest attribute
        // alone, so the screen would flip on wrist movement.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // Keep the screen on for the whole short capture so it can't
        // dim mid-utterance.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Scaffold {
                    PttCapture(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun PttCapture(onClose: () -> Unit) {
    val ctx = LocalContext.current
    // init | listening | sent | nospeech | error | denied
    var phase by remember { mutableStateOf("init") }
    var partial by remember { mutableStateOf("") }
    var recognizer: SpeechRecognizer? by remember { mutableStateOf(null) }

    fun begin() {
        phase = "listening"
        partial = ""
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                phase = "error"
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    sendToPhone(ctx, text)
                    partial = text
                    phase = "sent"
                } else {
                    phase = "nospeech"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            },
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) begin() else phase = "denied" }

    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy() }
    }

    // Auto-start the moment the activity opens — that's the whole point
    // of a hardware-key action: press → listening, no extra taps.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) begin() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Self-close once we reach a terminal phase.
    LaunchedEffect(phase) {
        when (phase) {
            "sent" -> { delay(1100); onClose() }
            "nospeech", "error" -> { delay(1400); onClose() }
            "denied" -> { delay(1600); onClose() }
        }
    }

    val statusText = when (phase) {
        "init" -> "starting…"
        "listening" -> "listening…"
        "sent" -> "sent ✓"
        "nospeech" -> "no speech"
        "error" -> "couldn't hear that"
        "denied" -> "mic permission needed"
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "MYTHARA · PTT", color = PURPLE, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (phase == "listening") BOK else PURPLE)
                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (phase == "sent") "✓" else "🎤",
                color = Color.Black,
                fontSize = 32.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = partial.ifBlank { statusText },
            color = if (partial.isBlank()) Color(0xFF999999) else Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}
