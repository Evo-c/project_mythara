package com.mythara.agent

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Looks up durable facts that are relevant to whatever the user just
 * typed and surfaces them to the chat agent as a system-prompt
 * addendum. The user-visible half of M8.3 SelfOrganizer — the recall
 * side. Periodic organisation (cluster / promote / demote nightly via
 * WorkManager) ships in a follow-up.
 *
 * Pipeline per user turn:
 *   1. [LocalEmbedder] encodes the user's message into a 100-dim
 *      L2-normalised vector.
 *   2. All semantic-tier records with embeddings are scanned linearly
 *      — small vaults stay well under 1ms; we'll swap for an ANN
 *      index when vaults grow past ~5k records.
 *   3. Each candidate's cosine similarity is multiplied by a
 *      reinforcement boost (high `seen` counter ≈ "the user has
 *      mentioned this multiple times") and an exponential time-decay
 *      (half-life ~30 days, so a fact stays salient for about a
 *      season before it starts fading from default recall).
 *   4. Top-K above [MIN_COSINE] threshold are returned.
 *
 * Format for the system prompt is short and unprefaced: the goal is
 * for the model to weave the facts into a natural answer without
 * quoting them. The string is *never* persisted to history — it
 * exists only for the duration of one user turn.
 */
@Singleton
class SemanticRecall @Inject constructor(
    private val embedder: LocalEmbedder,
    private val vault: LearningVault,
) {

    data class RecalledFact(
        val content: String,
        val cosine: Float,
        val finalScore: Float,
        val tsMillis: Long,
        val seen: Int,
        val src: String,
    )

    /**
     * Search the semantic tier for facts most relevant to [query].
     * Returns empty list if the embedder isn't ready (USE-Lite not
     * downloaded yet) or no records cross the similarity threshold.
     */
    suspend fun recall(
        query: String,
        topK: Int = TOP_K,
        threshold: Float = MIN_COSINE,
    ): List<RecalledFact> {
        if (query.isBlank()) return emptyList()
        if (!embedder.isReady()) {
            Log.d(TAG, "embedder not ready; skipping recall")
            return emptyList()
        }
        val queryVec = runCatching { embedder.embed(query) }.getOrElse {
            Log.w(TAG, "embed failed: ${it.message}")
            return emptyList()
        }
        // Pull from both semantic (durable facts) AND episodic
        // (Gemma-summarised conversation windows). Episodic carries
        // longer time-context — "what we discussed last Tuesday" —
        // which complements the fact-shaped semantic tier nicely.
        val records: List<LearningEntity> = buildList {
            addAll(vault.listByTier(Tier.Semantic, limit = SCAN_LIMIT))
            addAll(vault.listByTier(Tier.Episodic, limit = SCAN_LIMIT))
        }.filter { it.embedding != null }
        if (records.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val scored = records.mapNotNull { entity ->
            val vec = runCatching { LocalEmbedder.decode(entity.embedding!!) }.getOrNull()
                ?: return@mapNotNull null
            if (vec.size != queryVec.size) return@mapNotNull null
            val sim = LocalEmbedder.cosine(queryVec, vec)
            if (sim < threshold) return@mapNotNull null
            val daysOld = ((now - entity.lastSeenMs).coerceAtLeast(0L)) / DAY_MS
            val timeDecay = exp(-daysOld / HALF_LIFE_DAYS).toFloat()
            val seenBoost = 1f + SEEN_WEIGHT * (entity.seen - 1).coerceAtLeast(0)
            RecalledFact(
                content = entity.content,
                cosine = sim,
                finalScore = sim * timeDecay * seenBoost,
                tsMillis = entity.tsMillis,
                seen = entity.seen,
                src = entity.src,
            )
        }
        return scored.sortedByDescending { it.finalScore }.take(topK)
    }

    /**
     * Render a list of recalled facts as a single short system-prompt
     * block. Returns null if nothing to render — caller skips prefacing
     * the prior history with anything.
     */
    fun render(facts: List<RecalledFact>): String? {
        if (facts.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("Context from your durable memory of past conversations with this user. ")
        sb.append("Use it to inform your reply naturally; don't quote it back verbatim.\n\n")
        for (f in facts) {
            sb.append("- ").append(f.content)
            if (f.seen > 1) sb.append(" (reinforced ${f.seen}×)")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Looks at semantic records written in the last [windowMs] and
     * extracts the dominant `mood:` facet pattern. Returns the
     * dominant mood label (e.g. "anxious") if one mood accounts for
     * ≥50% of the window's facets, else null when readings are too
     * mixed to call. Empty vault or no Gemma-extracted records
     * yet → also null.
     *
     * Cheap — linear scan of recent records. Calls listByTier with a
     * window cap. Caller is expected to be on a coroutine (this
     * indirectly hits the Room DAO).
     */
    suspend fun recentMoodTrend(windowMs: Long = 6 * 3600 * 1000L): String? {
        val cutoff = System.currentTimeMillis() - windowMs
        val recent = vault.listByTier(Tier.Semantic, limit = MOOD_SCAN_LIMIT)
            .filter { it.tsMillis >= cutoff }
        if (recent.isEmpty()) return null
        val moods = recent.mapNotNull { entity ->
            vault.decodeFacets(entity)
                .firstOrNull { it.startsWith("mood:") }
                ?.removePrefix("mood:")
        }.filter { it != "unknown" && it.isNotBlank() }
        if (moods.isEmpty()) return null
        val histogram = moods.groupingBy { it }.eachCount()
        val total = moods.size
        val (topMood, topCount) = histogram.maxBy { it.value }
        return if (topCount.toDouble() / total >= MOOD_DOMINANCE_THRESHOLD) topMood else null
    }

    /**
     * Format a mood trend as a one-line system-prompt addendum. The
     * model is instructed to *respond* with awareness of the user's
     * state — not to mirror the mood word-for-word, just to let it
     * shape tone choices.
     */
    fun renderMoodSystemMessage(moodTrend: String?): String? {
        if (moodTrend.isNullOrBlank()) return null
        val guidance = when (moodTrend) {
            "anxious", "frustrated", "sad" ->
                "The user seems $moodTrend in recent conversations. Be warm and supportive; avoid pushing topics that could amplify stress."
            "excited", "happy" ->
                "The user has been $moodTrend lately. Feel free to match their energy a bit."
            "calm", "neutral" ->
                "The user has been $moodTrend recently. Default conversational tone."
            else -> "The user's recent emotional state appears: $moodTrend."
        }
        return "User emotional context. $guidance"
    }

    companion object {
        private const val TAG = "Mythara/Recall"

        /** How many top-scoring facts to surface per turn. */
        const val TOP_K = 6

        /** Records below this raw cosine similarity never make the cut. */
        const val MIN_COSINE = 0.45f

        /** Hard scan cap; bumps to an ANN index when we exceed this. */
        const val SCAN_LIMIT = 500

        /** Cap on records the mood-trend scan walks. */
        const val MOOD_SCAN_LIMIT = 200

        /** Fraction of the window a single mood must own to count as "dominant". */
        const val MOOD_DOMINANCE_THRESHOLD = 0.5

        /** Reinforcement weight: seen=1 → 1.0×, seen=5 → 1.4×, seen=11 → 2.0×. */
        const val SEEN_WEIGHT = 0.1f

        /**
         * Time-decay half-life. A fact last reinforced 30 days ago
         * weighs 0.5× as much as one reinforced today, 0.25× at 60
         * days, etc. Chosen so seasonal context stays warm but old
         * one-off mentions drop out.
         */
        const val HALF_LIFE_DAYS = 30.0
        private const val DAY_MS = 24.0 * 3600.0 * 1000.0
    }
}
