package com.mythara.agent

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.minimax.VisionService
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Throttled, contact-scoped ingestor for images attached to incoming
 * notification messages.
 *
 * Pipeline per image:
 *   1. Wait at least [MIN_GAP_MS] since the previous ingest — avoids
 *      blasting the vision API when a friend sends 5 photos in a row.
 *   2. Call [VisionService.describeImage]. Routes to whichever vision
 *      model the user has configured (Gemini preferred, else
 *      MiniMax-VL-01). Same throttled service the bulk WhatsApp
 *      zip importer uses.
 *   3. Feed the description through [GemmaExtractor.extractWithMood].
 *      Gemma is told (via its existing prompt) to return empty facts
 *      for memes / ads / quotes / forwards / generic content. We
 *      respect that — empty facts ⇒ image was forgettable, just
 *      delete it and move on.
 *   4. For non-empty facts, persist each into the learning vault
 *      facetted with the sender contact + source app + the
 *      "notification-image" trait so the per-contact recall scope
 *      can find them later.
 *   5. Delete the staged image file regardless of outcome. Raw image
 *      bytes never persist — only the extracted text traits.
 *
 * Queue + single-worker design: a `Channel<Job>` collects requests,
 * one consumer coroutine drains them sequentially with the
 * inter-image delay in between. Survives Notification posts faster
 * than we can process — the channel buffers up to [QUEUE_CAPACITY],
 * older items dropped on overflow (newer image arrivals are usually
 * more interesting anyway).
 *
 * Process-scoped via @Singleton; started by [MytharaApp.onCreate]
 * alongside the other autopilot dispatchers.
 */
@Singleton
class NotificationImageIngestor @Inject constructor(
    private val vision: VisionService,
    private val gemma: GemmaExtractor,
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
) {
    private data class Job(
        val imagePath: String,
        val senderName: String,
        val sourceApp: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Job>(capacity = QUEUE_CAPACITY)

    @Volatile private var started = false
    @Volatile private var lastProcessedAt: Long = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch { drain() }
        Log.d(TAG, "NotificationImageIngestor started")
    }

    /**
     * Enqueue all images attached to a notification for processing.
     * Returns immediately — work happens on the singleton's
     * coroutine. Called from [AutoReplyDispatcher] once eligibility
     * gates (not a group, not automated sender, messaging app) pass.
     */
    fun enqueue(imagePaths: List<String>, senderName: String, sourceApp: String) {
        if (imagePaths.isEmpty()) return
        for (path in imagePaths) {
            val sent = queue.trySend(Job(path, senderName, sourceApp))
            if (!sent.isSuccess) {
                // Channel was full — drop this image but make sure the
                // staged file gets cleaned up so we don't leak disk.
                Log.d(TAG, "queue full, dropping image $path")
                runCatching { File(path).delete() }
            }
        }
    }

    private suspend fun drain() {
        for (job in queue) {
            // Pace ourselves between processed images.
            val now = System.currentTimeMillis()
            val sinceLast = now - lastProcessedAt
            if (lastProcessedAt > 0 && sinceLast < MIN_GAP_MS) {
                delay(MIN_GAP_MS - sinceLast)
            }
            processOne(job)
            lastProcessedAt = System.currentTimeMillis()
        }
    }

    private suspend fun processOne(job: Job) {
        val file = File(job.imagePath)
        if (!file.exists()) return

        try {
            val outcome = runCatching {
                vision.describeImage(file, prompt = INGEST_PROMPT)
            }.getOrNull()

            if (outcome == null || !outcome.ok || outcome.text.isBlank()) {
                Log.d(TAG, "vision unavailable / failed for ${file.name}: ${outcome?.code}")
                return
            }
            val description = outcome.text.trim()

            // Pre-screen on the vision description itself — if it
            // already says "forwarded content; no personal signal"
            // or similar, skip Gemma entirely.
            if (looksLikeForward(description)) {
                Log.d(TAG, "skipping image (looks like forward/meme): ${file.name}")
                return
            }

            // Gemma extracts durable facts. For irrelevant content it
            // returns empty — which is the desired signal here too.
            val facts = if (gemma.isReady()) {
                runCatching { gemma.extractWithMood(description).facts }.getOrDefault(emptyList())
            } else {
                // No Gemma loaded — fall back to a single low-confidence
                // raw description record only when the description
                // doesn't smell like a forward (already filtered above).
                emptyList()
            }

            if (facts.isEmpty() && !gemma.isReady()) {
                // Heuristic fallback to a single trait so the vision
                // call wasn't wasted. Only when description has
                // non-trivial content.
                writeRawDescription(description, job, lowConf = true)
                return
            }
            if (facts.isEmpty()) {
                Log.d(TAG, "no durable facts in image from ${job.senderName}; skipping")
                return
            }

            val now = System.currentTimeMillis()
            for (fact in facts) {
                val content = fact.content.trim()
                if (content.isBlank()) continue
                val embedding = runCatching {
                    if (embedder.isReady()) embedder.embed(content) else null
                }.getOrNull()
                val facets = buildList {
                    add("kind:notification-image")
                    add("source:notification")
                    add("contact:${job.senderName}")
                    add("app:${job.sourceApp}")
                    add("trait:gemma-extracted-image")
                    addAll(fact.facets)
                }
                runCatching {
                    vault.add(
                        content = content,
                        tier = Tier.Semantic,
                        src = "notif-image:${job.sourceApp}",
                        facets = facets,
                        embedding = embedding,
                        embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                        conf = 0.75,
                        now = now,
                    )
                }
            }
            Log.d(TAG, "ingested image from ${job.senderName}: ${facts.size} fact(s)")
        } finally {
            // Always delete the staged image — raw bytes never persist.
            runCatching { file.delete() }
        }
    }

    private suspend fun writeRawDescription(
        description: String,
        job: Job,
        lowConf: Boolean,
    ) {
        val content = description.take(MAX_RAW_LEN).trim()
        if (content.isBlank()) return
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = listOf(
            "kind:notification-image",
            "source:notification",
            "contact:${job.senderName}",
            "app:${job.sourceApp}",
            "trait:image-description",
        )
        runCatching {
            vault.add(
                content = "Image from ${job.senderName}: $content",
                tier = Tier.Semantic,
                src = "notif-image:${job.sourceApp}",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = if (lowConf) 0.45 else 0.6,
                now = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Description-text heuristic for "this is a meme / forward / ad
     * / quote" — the vision prompt explicitly asks the model to say
     * "forwarded content; no personal signal" for that class, but the
     * model doesn't always cooperate; this catches the common
     * variations as a defensive filter.
     */
    private fun looksLikeForward(desc: String): Boolean {
        val l = desc.lowercase()
        val markers = arrayOf(
            "forwarded content", "no personal signal", "generic meme",
            "appears to be a meme", "looks like an ad", "promotional image",
            "stock photo", "inspirational quote", "motivational quote",
            "text overlay reads", "watermark", "shutterstock", "screenshot of",
        )
        return markers.any { l.contains(it) }
    }

    companion object {
        private const val TAG = "Mythara/NotifImg"
        private const val MIN_GAP_MS = 30_000L     // 30s between vision calls
        private const val QUEUE_CAPACITY = 16
        private const val MAX_RAW_LEN = 240

        /**
         * Vision prompt tuned to elicit signals the downstream
         * heuristic + Gemma can rely on. Tells the model the image
         * came from a chat so the description is grounded in
         * "what does this image tell me about the people involved"
         * rather than literal pixel description.
         */
        private const val INGEST_PROMPT =
            "This image was shared in a private chat by someone the user knows. " +
                "Describe it in 1-2 sentences focused on what it reveals about THE SENDER OR THE USER " +
                "— a moment in their life, a place, an activity, a pet, a personal milestone. " +
                "If it's a meme, a screenshot, a forwarded ad, a quote on a coloured background, " +
                "a promotional / stock image, or any generic mass-forwarded content with no personal signal, " +
                "say the exact phrase 'forwarded content; no personal signal' and nothing else. " +
                "Never speculate, never invent details, never describe what the image is 'supposed' to represent."
    }
}
