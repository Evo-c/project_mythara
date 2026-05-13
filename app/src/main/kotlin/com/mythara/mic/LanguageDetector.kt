package com.mythara.mic

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * On-device language detector. Wraps ML Kit's Language Identification
 * client (~2MB model, runs in ~10ms) and exposes a suspend API so
 * coroutine call sites can drop a `.identify(text)` mid-pipeline.
 *
 * Returns BCP-47 tags (`en`, `es`, `zh`, `hi`, `tr`, …) or null when
 * ML Kit returns `und` (no language confidently identified — usually
 * happens on text under ~10 characters or pure punctuation).
 *
 * Confidence threshold is conservatively 0.5 so we only commit when
 * the detection is strong; below that we'd rather speak in the system
 * locale than guess wrong and pick a weird-sounding voice.
 */
@Singleton
class LanguageDetector @Inject constructor() {

    private val client by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build(),
        )
    }

    /** BCP-47 tag (e.g. `"en"`, `"es"`, `"hi"`) or null when undetermined. */
    suspend fun identify(text: String): String? {
        if (text.isBlank()) return null
        return suspendCancellableCoroutine { cont ->
            client.identifyLanguage(text)
                .addOnSuccessListener { tag ->
                    val result = if (tag.isNullOrBlank() || tag == "und") null else tag
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "language id failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    /** Same as [identify] but parsed into a [Locale] for TTS / SpeechRecognizer. */
    suspend fun identifyLocale(text: String): Locale? {
        val tag = identify(text) ?: return null
        return runCatching { Locale.forLanguageTag(tag) }.getOrNull()
    }

    companion object { private const val TAG = "Mythara/Lang" }
}
