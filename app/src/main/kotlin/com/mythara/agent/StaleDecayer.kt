package com.mythara.agent

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningDao
import com.mythara.secret.observe.vault.LearningEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nightly maintenance pass that gently decays the confidence of
 * stale, never-reinforced semantic facts and eventually deletes the
 * dregs. The third pillar of [SelfOrganizerWorker], alongside the
 * sha-dedup pass and Gemma-driven episodic promotion.
 *
 * Why decay instead of immediate deletion:
 *   - Recall already uses an exponential time-decay weight on cosine
 *     similarity (half-life ~30 days), so a stale record is mostly
 *     ignored at retrieval time anyway. Confidence on the record
 *     itself is the lever for *dedup* and *promotion* decisions —
 *     halving it keeps the record visible to debugging while
 *     stopping it from biasing those passes.
 *   - Deletion is irreversible. The vault is private to one user
 *     and one device's storage; "I deleted a year-old fact you
 *     mentioned once" beats "I forgot it forever, sorry" only
 *     after several decay rounds confirm nobody cares.
 *
 * Behaviour per run:
 *   - tier-`s` records older than [STALE_AGE_MS] with `seen == 1`
 *     have their confidence halved (clamped at [MIN_DECAYED_CONF]
 *     above zero) — so over ~5 nightly passes a fact at conf 0.5
 *     drops to 0.5 → 0.25 → 0.125 → 0.0625 → 0.03125 → 0.015 ...
 *   - records that already meet the criteria AND have conf below
 *     [DELETE_FLOOR] are deleted outright.
 *   - semantic and episodic only — working-tier transcripts are
 *     auto-purged on disk by [com.mythara.secret.observe.RawDataPurger]
 *     already, no need to second-guess them here.
 */
@Singleton
class StaleDecayer @Inject constructor(
    private val dao: LearningDao,
) {
    data class Report(
        val candidatesScanned: Int,
        val confidenceDecayed: Int,
        val recordsDeleted: Int,
    )

    suspend fun decay(): Report {
        val now = System.currentTimeMillis()
        val cutoff = now - STALE_AGE_MS
        val records = dao.listByTier(Tier.Semantic.code, limit = SCAN_LIMIT)
            .filter { it.seen == 1 && it.lastSeenMs < cutoff }
        if (records.isEmpty()) {
            return Report(candidatesScanned = 0, confidenceDecayed = 0, recordsDeleted = 0)
        }

        var decayed = 0
        var deleted = 0
        for (r in records) {
            if (r.conf < DELETE_FLOOR) {
                runCatching { dao.deleteById(r.id) }
                    .onSuccess { deleted++ }
                continue
            }
            val newConf = (r.conf * DECAY_FACTOR).coerceAtLeast(MIN_DECAYED_CONF)
            runCatching { dao.update(r.copy(conf = newConf)) }
                .onSuccess {
                    decayed++
                    Log.d(TAG, "decayed ${r.id} ${r.conf.format()} → ${newConf.format()}")
                }
        }
        return Report(records.size, decayed, deleted)
    }

    private fun Double.format(): String = String.format("%.4f", this)

    companion object {
        private const val TAG = "Mythara/Decay"

        /** A record must be at least this old before it's eligible to decay. */
        const val STALE_AGE_MS = 365L * 24 * 60 * 60 * 1000  // 1 year

        /** Multiplier applied per run — 0.5 = half-life of one cycle. */
        const val DECAY_FACTOR = 0.5

        /** Absolute floor for the decay — protects records from going to 0. */
        const val MIN_DECAYED_CONF = 0.01

        /** Records below this confidence are deleted on the next decay pass. */
        const val DELETE_FLOOR = 0.02

        /** Cap on records examined per run. */
        const val SCAN_LIMIT = 5000
    }
}
