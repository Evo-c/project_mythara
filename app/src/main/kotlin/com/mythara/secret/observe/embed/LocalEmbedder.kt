package com.mythara.secret.observe.embed

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe-backed on-device sentence embedder. Mythara never sends text
 * to a remote embedding API — every learning gets its 100-dim Universal
 * Sentence Encoder vector computed locally and stored alongside the
 * source text.
 *
 * Lifecycle:
 *   - cold init lazily on first [embed] call (model file must be on disk
 *     already; otherwise we throw — caller should ensure
 *     [EmbeddingsModelStore.ensureReady] succeeded first)
 *   - one [TextEmbedder] instance reused across calls (cheaper than
 *     re-loading the tflite per text)
 *   - [release] closes the underlying handle; idempotent
 *
 * Storage helper [encode] / [decode] convert FloatArray ↔ ByteArray
 * (little-endian float32). Suitable for `.vec` sidecar files; also for
 * base64 in MemoryRecord syncs.
 */
@Singleton
class LocalEmbedder @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: EmbeddingsModelStore,
) {

    @Volatile private var embedder: TextEmbedder? = null

    fun isReady(): Boolean = store.isAvailable()

    @Synchronized
    private fun ensureEmbedder(): TextEmbedder {
        embedder?.let { return it }
        val path = store.pathOrNull() ?: error("Embedding model not on disk — call EmbeddingsModelStore.ensureReady() first")
        Log.d(TAG, "loading TextEmbedder from $path")
        val options = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath(path).build(),
            )
            // L2-normalise the output vector so cosine == dot product
            // (faster downstream similarity).
            .setL2Normalize(true)
            .setQuantize(false)
            .build()
        val e = TextEmbedder.createFromOptions(ctx, options)
        embedder = e
        return e
    }

    /**
     * Run inference and return the 100-dim float vector. Throws if the
     * model isn't available or if MediaPipe returns no embeddings.
     */
    fun embed(text: String): FloatArray {
        val e = ensureEmbedder()
        val result = e.embed(text)
        val embeddings: List<Embedding> = result.embeddingResult().embeddings()
        val first = embeddings.firstOrNull() ?: error("No embedding produced")
        return first.floatEmbedding()
    }

    fun release() {
        runCatching { embedder?.close() }
        embedder = null
    }

    companion object {
        private const val TAG = "Mythara/Embed"

        /** Convert FloatArray → little-endian float32 ByteArray. */
        fun encode(vec: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in vec) buf.putFloat(v)
            return buf.array()
        }

        /** Convert little-endian float32 ByteArray → FloatArray. */
        fun decode(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes.size / 4)
            for (i in out.indices) out[i] = buf.float
            return out
        }

        /** Cosine similarity for L2-normalised vectors = simple dot product. */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
