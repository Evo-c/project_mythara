package com.mythara.minimax

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device vision via Gemma 4 E2B (LiteRT-LM).
 *
 * Preferred path in [VisionService] when the model is loaded — no
 * API key, no network round-trip, image bytes never leave the
 * device. Gemma 4 is multimodal-trained (text + image + audio);
 * LiteRT-LM 0.11+ exposes image input via `Content.ImageBytes`
 * which we pass alongside the prompt in the same `Message`.
 *
 * Reuses [GemmaExtractor]'s engine + inference mutex so multimodal
 * captions don't fight the text-only extraction paths (persona
 * traits, summaries) for the single LiteRT-LM session that Gemma
 * supports concurrently.
 *
 * Falls through silently when:
 *   - the Gemma model file isn't installed yet
 *   - the image can't be decoded (corrupt JPEG, etc.)
 *   - the inference crashes or returns empty
 * In all these cases [describeImage] returns `Outcome(ok=false, …)`
 * and [VisionService] cascades to cloud Gemini → MiniMax-VL.
 *
 * Why we DON'T use AICore (Google's Gemini Nano SDK): the current
 * AICore release (0.0.1-exp01) is text-only — no image Parts in
 * its content DSL. LiteRT-LM with Gemma 4 is the working on-device
 * VLM path on Android right now, and we already have the model
 * loaded for persona analysis, so this is a pure reuse.
 */
@Singleton
class GemmaVisionService @Inject constructor(
    private val gemma: GemmaExtractor,
) {

    data class Outcome(
        val ok: Boolean,
        val text: String,
        val code: String? = null,
    )

    /** Is the Gemma model loaded + ready to handle a vision call?
     *  Cheap — just delegates to the model store's file-exists
     *  check. Doesn't actually warm the interpreter. */
    fun isAvailable(): Boolean = gemma.isReady()

    suspend fun describeImage(
        imageFile: File,
        prompt: String,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext Outcome(false, "image file missing or empty", code = "no_image")
        }
        if (!gemma.isReady()) {
            return@withContext Outcome(false, "Gemma model not installed", code = "not_ready")
        }
        // Downsample on decode + re-encode as JPEG so the bytes we
        // hand Gemma are bounded. Gemma 4's vision encoder works
        // best with ≤ 1024 px on the long edge; bigger images waste
        // tokens for the vision tokeniser.
        val bytes = runCatching { downsampleToJpeg(imageFile) }.getOrNull()
            ?: return@withContext Outcome(false, "could not decode image", code = "decode_failed")

        val reply = gemma.describeImage(bytes, prompt)
        if (reply.isNullOrBlank()) {
            return@withContext Outcome(false, "empty response from Gemma", code = "empty")
        }
        Outcome(ok = true, text = reply.trim())
    }

    /** Decode the JPEG, downsample so the long edge is ≤ 1024 px,
     *  re-encode at 85% JPEG quality. Bounds the byte buffer we
     *  send through the LiteRT-LM image content path so a 12 MP
     *  phone photo doesn't blow up the prompt budget. */
    private fun downsampleToJpeg(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return null
        var sample = 1
        while (srcLong / sample > MAX_LONG_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }.also {
            Log.v(TAG, "downsampled ${file.length()}B → ${it.size}B (sample=$sample)")
        }
    }

    companion object {
        private const val TAG = "Mythara/GemmaVision"
        private const val MAX_LONG_EDGE_PX = 1024
        private const val JPEG_QUALITY = 85
    }
}
