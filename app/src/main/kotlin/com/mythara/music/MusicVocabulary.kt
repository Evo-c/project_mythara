package com.mythara.music

import android.util.Log
import com.mythara.data.MusicVocabularyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The shared vocabulary that the user and agent develop together in
 * Music Mode. Translates content tokens (lowercased + stripped) into
 * [Motif]s. New tokens are minted deterministically from a hash of
 * the token over the [PENTATONIC] scale, so the same word always
 * sounds the same on first encounter — which is the whole point: the
 * vocabulary has to be predictable for the user to learn it.
 *
 * The [confidenceFlow] / [reinforce] surface lets the chat UI feed
 * the user's decode-tap signal back: a "got it!" tap calls
 * [reinforce(token, hit=true)]; a "show me" tap calls it with
 * `hit=false`. After enough misses (or low confidence), [reinforce]
 * mutates the motif onto a fresh pattern (incrementing
 * [Motif.generation]), giving learnability another shot.
 *
 * State is persisted in [MusicVocabularyStore] (a DataStore JSON
 * blob) — high-frequency mutable state lives there rather than in
 * the [com.mythara.secret.observe.vault.LearningVault], which is
 * reserved for durable knowledge.
 */
@Singleton
class MusicVocabulary @Inject constructor(
    private val store: MusicVocabularyStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache: MutableMap<String, Motif> = mutableMapOf()
    private val cacheLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var loaded = false
    @Volatile private var flushJob: Job? = null

    private val _vocab = MutableStateFlow<Map<String, Motif>>(emptyMap())
    /** Hot view of the entire vocabulary — for the inspector UI to
     *  render a list / count. */
    val vocab: StateFlow<Map<String, Motif>> = _vocab.asStateFlow()

    init {
        // Hydrate from disk eagerly so the first agent reply doesn't
        // pay a DataStore round-trip per token.
        scope.launch { ensureLoaded() }
    }

    /** Get the motif for [token], minting a fresh one if the vocabulary
     *  hasn't seen it yet. Token is normalised internally (lowercased,
     *  punctuation stripped, leading/trailing whitespace dropped). */
    suspend fun motifFor(token: String): Motif {
        val key = normalise(token)
        if (key.isEmpty()) return SILENT_MOTIF
        ensureLoaded()
        cacheLock.withLock {
            cache[key]?.let { return it }
            val fresh = mintMotif(key, generation = 0)
            cache[key] = fresh
            scheduleFlush()
            _vocab.value = cache.toMap()
            return fresh
        }
    }

    /** Record a user's decode-tap signal. [hit] = the user identified
     *  the motif before revealing the text. Updates persisted on the
     *  next debounced flush. */
    suspend fun reinforce(token: String, hit: Boolean) {
        val key = normalise(token)
        if (key.isEmpty()) return
        ensureLoaded()
        cacheLock.withLock {
            val existing = cache[key] ?: return
            val updated = if (hit) {
                existing.copy(hits = existing.hits + 1)
            } else {
                // Miss path: bump misses; if confidence has decayed past
                // the floor and we have enough signal, mutate to a
                // fresh pitch pattern (next generation) so the user
                // gets a new shot at learning it.
                val nextMisses = existing.misses + 1
                val total = existing.hits + nextMisses
                if (total >= MIN_RESHAPE_SIGNALS &&
                    (existing.hits.toFloat() / total) < RESHAPE_CONFIDENCE_FLOOR
                ) {
                    Log.d(
                        TAG,
                        "reshaping motif for '$key' (gen ${existing.generation} → " +
                            "${existing.generation + 1}, conf=${existing.confidence})",
                    )
                    mintMotif(key, generation = existing.generation + 1)
                } else {
                    existing.copy(misses = nextMisses)
                }
            }
            cache[key] = updated
            _vocab.value = cache.toMap()
            scheduleFlush()
        }
    }

    /** Debounced persistence — many reinforce calls in quick
     *  succession (e.g. while a long reply plays out) collapse into a
     *  single DataStore write. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        val snapshot = cacheLock.withLock { cache.toMap() }
        val obj = buildJsonObject {
            for ((k, m) in snapshot) {
                put(k, buildJsonObject {
                    put("n", buildJsonArray { m.notes.forEach { add(it) } })
                    put("h", m.hits)
                    put("m", m.misses)
                    put("g", m.generation)
                })
            }
        }
        runCatching { store.setVocab(obj.toString()) }
            .onFailure { Log.w(TAG, "vocab persist failed: ${it.message}") }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        cacheLock.withLock {
            if (loaded) return
            val raw = runCatching { store.vocabFlow().first() }.getOrDefault("{}")
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
            for ((k, v) in parsed) {
                runCatching {
                    val obj = v.jsonObject
                    val notes = obj["n"]?.jsonArray?.map { it.jsonPrimitive.float } ?: emptyList()
                    val h = obj["h"]?.jsonPrimitive?.int ?: 0
                    val m = obj["m"]?.jsonPrimitive?.int ?: 0
                    val g = obj["g"]?.jsonPrimitive?.int ?: 0
                    if (notes.isNotEmpty()) cache[k] = Motif(notes, h, m, g)
                }.onFailure { Log.w(TAG, "skipping malformed vocab entry '$k': ${it.message}") }
            }
            // One-shot migration: any motif whose notes don't come
            // from the current OM-harmonic scale was minted on the
            // legacy pentatonic — reshape it now so colour lookups
            // and the underlying tone playback both line up. Keeps
            // hit/miss history but bumps the generation so the user
            // gets a fresh pitch pattern that matches the new scale.
            val omSet = OM_HARMONICS.toSet()
            val toMigrate = cache.filter { (_, m) -> m.notes.any { it !in omSet } }
            if (toMigrate.isNotEmpty()) {
                for ((key, old) in toMigrate) {
                    val fresh = mintMotif(key, generation = old.generation + 1)
                    cache[key] = fresh.copy(hits = old.hits, misses = old.misses)
                }
                Log.d(TAG, "migrated ${toMigrate.size} motif(s) onto OM-harmonic scale")
                scheduleFlush()
            }
            _vocab.value = cache.toMap()
            loaded = true
            Log.d(TAG, "loaded vocabulary: ${cache.size} motif(s)")
        }
    }

    /** Deterministic motif minter. Uses a stable hash of the token (+
     *  generation, so reshaped motifs differ from their predecessor)
     *  to pick [NOTES_PER_MOTIF] notes from the [OM_HARMONICS] scale. */
    private fun mintMotif(key: String, generation: Int): Motif {
        val seed = stableHash("$key|$generation")
        val notes = (0 until NOTES_PER_MOTIF).map { i ->
            val mixed = seed.rotateLeft(i * 7) xor (i.toLong() * 0x9E3779B9L)
            OM_HARMONICS[(abs(mixed) % OM_HARMONICS.size).toInt()]
        }
        return Motif(notes = notes, hits = 0, misses = 0, generation = generation)
    }

    private fun stableHash(s: String): Long {
        // FNV-1a 64-bit. Avoids dependency on JVM hashCode flakiness
        // across process restarts (which would re-mint motifs on app
        // upgrade — exactly the wrong behaviour for a learnable
        // vocabulary).
        var h = -3750763034362895579L // 0xCBF29CE484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    private fun normalise(token: String): String {
        return token.lowercase()
            .replace(NON_WORD, "")
            .trim()
    }

    companion object {
        private const val TAG = "Mythara/MusicVocab"

        /** Number of notes per motif — short enough to feel like a
         *  single "word", long enough to be distinguishable. */
        const val NOTES_PER_MOTIF = 3

        /** Harmonic series of 136.1 Hz — the Sanskrit "OM" base
         *  frequency (Hans Cousto's calculation, derived from Earth's
         *  orbital period via successive octave reductions; long
         *  associated with the OM syllable in Indian classical and
         *  contemporary sound-healing practice). Skipping the
         *  fundamental (136.1 Hz, below the engine's MIN_HZ band)
         *  and starting at the 2nd harmonic — every pitch here is
         *  consonant with every other, since they all sit on the
         *  natural overtone series of one root.
         *
         *  2nd  272.2 Hz   octave above OM
         *  3rd  408.3 Hz   perfect-fifth above the octave
         *  4th  544.4 Hz   two octaves above OM
         *  5th  680.5 Hz   two octaves + major-third
         *  6th  816.6 Hz   two octaves + perfect-fifth
         *  7th  952.7 Hz   ≈ minor-seventh above the 4th
         *  8th  1088.8 Hz  three octaves above OM
         *  9th  1224.9 Hz  three octaves + major-second */
        val OM_HARMONICS: List<Float> = listOf(
            272.2f, 408.3f, 544.4f, 680.5f,
            816.6f, 952.7f, 1088.8f, 1224.9f,
        )

        /** Backwards-compat alias — keeps any callers that still
         *  reference the old name compiling. */
        @Deprecated("Use OM_HARMONICS", ReplaceWith("OM_HARMONICS"))
        val PENTATONIC: List<Float> get() = OM_HARMONICS

        /** Sentinel for tokens that normalise to the empty string. */
        val SILENT_MOTIF = Motif(notes = emptyList())

        /** Reshape a motif when confidence < [RESHAPE_CONFIDENCE_FLOOR]
         *  AND we've had at least this much user feedback for it. */
        private const val MIN_RESHAPE_SIGNALS = 3
        private const val RESHAPE_CONFIDENCE_FLOOR = 0.34f

        /** DataStore writes are debounced — a long reply may reinforce
         *  many tokens; only flush once after the burst settles. */
        private const val FLUSH_DEBOUNCE_MS = 1_500L

        private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    }
}
