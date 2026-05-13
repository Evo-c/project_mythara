package com.mythara.secret.observe.embed

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy-fetches Google's Universal Sentence Encoder Lite .tflite model
 * that powers our local-first embedding pipeline (M8.1c).
 *
 * On-device path:
 *   filesDir/embed-models/universal-sentence-encoder.tflite (~6MB)
 *
 * Compared to [VoskModelStore][com.mythara.secret.observe.vosk.VoskModelStore]
 * this is the easier case — a single file, no zip extract. We still
 * validate Content-Length on download (lesson from the Vosk truncated-zip
 * bug) and self-heal on retry.
 *
 * State machine and flow mirrors VoskModelStore: Missing → Downloading →
 * Ready / Failed. No Extracting state because there's nothing to unpack.
 */
@Singleton
class EmbeddingsModelStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
        }
        data class Ready(val path: String) : State
        data class Failed(val message: String) : State
    }

    val modelDir: File get() = ctx.filesDir.resolve("embed-models").apply { mkdirs() }
    val modelFile: File get() = modelDir.resolve(MODEL_NAME)
    private val sizeMarker: File get() = modelDir.resolve("$MODEL_NAME.size")

    private val _state = MutableStateFlow<State>(
        if (isAvailable()) State.Ready(modelFile.absolutePath) else State.Missing,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isAvailable(): Boolean {
        if (!modelFile.exists()) return false
        if (modelFile.length() < MIN_VALID_BYTES) return false
        if (!sizeMarker.exists()) return modelFile.length() >= MIN_VALID_BYTES
        val expected = sizeMarker.readText().trim().toLongOrNull() ?: return false
        return expected == modelFile.length()
    }

    fun pathOrNull(): String? = if (isAvailable()) modelFile.absolutePath else null

    suspend fun ensureReady(): State {
        if (isAvailable()) {
            _state.value = State.Ready(modelFile.absolutePath)
            return _state.value
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                download()
                State.Ready(modelFile.absolutePath).also { _state.value = it }
            }.getOrElse { e ->
                Log.e(TAG, "embed model fetch failed: ${e.message}", e)
                runCatching { if (modelFile.exists()) modelFile.delete() }
                runCatching { if (sizeMarker.exists()) sizeMarker.delete() }
                State.Failed(e.message ?: e.javaClass.simpleName).also { _state.value = it }
            }
        }
    }

    fun forgetModel() {
        runCatching {
            if (modelFile.exists()) modelFile.delete()
            if (sizeMarker.exists()) sizeMarker.delete()
        }
        _state.value = State.Missing
    }

    private fun download() {
        if (modelFile.exists()) modelFile.delete()
        if (sizeMarker.exists()) sizeMarker.delete()

        val req = Request.Builder().url(MODEL_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching embedder")
            val body = resp.body ?: error("empty body fetching embedder")
            val total = body.contentLength().coerceAtLeast(0L)
            _state.value = State.Downloading(0, total)
            body.byteStream().use { input ->
                FileOutputStream(modelFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _state.update { State.Downloading(read, total) }
                        }
                    }
                }
            }
            val downloadedBytes = modelFile.length()
            if (total > 0 && downloadedBytes != total) {
                modelFile.delete()
                error("download truncated: $downloadedBytes / $total")
            }
            if (downloadedBytes < MIN_VALID_BYTES) {
                modelFile.delete()
                error("download too small ($downloadedBytes bytes)")
            }
            sizeMarker.writeText(downloadedBytes.toString())
            Log.d(TAG, "embed model ready: $downloadedBytes bytes at ${modelFile.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "Mythara/Embed"
        // Google's official Universal Sentence Encoder Lite for MediaPipe
        // Tasks-Text. 100-dim float vectors, 6MB. Apache 2.0.
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"
        private const val MODEL_NAME = "universal-sentence-encoder.tflite"
        private const val MIN_VALID_BYTES = 3L * 1024 * 1024  // file is ~6MB; floor catches error pages
        private const val PROGRESS_REPORT_MS = 250L

        /** Identifier embedded in MemoryRecord.embModel so consumers know which model produced the vector. */
        const val MODEL_ID = "use-lite-v1"
        const val EMBEDDING_DIM = 100
    }
}
