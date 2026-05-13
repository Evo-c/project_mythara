package com.mythara.imports

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar

/**
 * Reduces a batch of imported [MessageRecord]s (SMS provider scan,
 * WhatsApp `.txt` export, future Signal / Telegram backups) into a
 * handful of persona-trait vault records.
 *
 * Privacy posture: we don't persist raw messages. Only the
 * extracted patterns — "top texting contact is Mom", "most active
 * texting hour is 9-10pm", "common phrases: lol / thanks / on my
 * way" — land in the vault. Those records sync to GitHub like the
 * usage-stats persona records.
 *
 * Two layers run in sequence:
 *   1. Cheap heuristics over the entire batch — top contacts, peak-hour
 *      band, style heuristics, totals. These always run and don't need
 *      Gemma to be loaded.
 *   2. If Gemma is available, a sampled chunk of the user's outgoing
 *      messages (the user's own voice, never inbound from other people)
 *      gets fed through the on-device LLM extractor to lift deeper
 *      traits — recurring topics, relationship dynamics, voice/tone
 *      patterns. This is the "way of communicating" pass the user
 *      explicitly asked for.
 *
 * Privacy posture: we don't persist raw messages. Only the extracted
 * traits — "top texting contact is Mom", "talks about gym a lot", "uses
 * dry humour" — land in the vault. Those records sync to GitHub like
 * the usage-stats persona records. Gemma runs ON-DEVICE; the messages
 * never leave the phone, even to MiniMax.
 */
@Singleton
class MessagePersonaExtractor @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    private val gemma: GemmaExtractor,
) {
    data class Report(
        val ok: Boolean,
        val recordsWritten: Int,
        val messagesAnalyzed: Int,
        val message: String? = null,
    )

    suspend fun extractAndPersist(
        source: String,
        messages: List<MessageRecord>,
    ): Report {
        if (messages.isEmpty()) {
            return Report(false, 0, 0, "no messages to analyse")
        }
        val now = System.currentTimeMillis()
        var written = 0

        // 1) Top contacts by user-sent message count. "Mostly texts
        //    Mom and Sam" is one of the most useful persona facts.
        val userMessages = messages.filter { it.isFromUser && !it.contact.isNullOrBlank() }
        val byContact = userMessages
            .groupingBy { it.contact!! }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        if (byContact.isNotEmpty()) {
            val top = byContact.take(TOP_CONTACTS).joinToString(", ") { (name, count) -> "$name ($count)" }
            addPersonaFact(
                content = "Top $source contacts the user messages: $top.",
                traits = listOf("trait:top-contacts", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 2) Peak hour band — which 4h window dominates outgoing
        //    message timestamps. Tells the agent about the user's
        //    rhythm ("you're a 9-11pm texter").
        val hourCounts = IntArray(24)
        val cal = Calendar.getInstance()
        for (m in userMessages) {
            cal.timeInMillis = m.tsMillis
            hourCounts[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        val (bandLabel, bandCount) = peakBand(hourCounts)
        if (bandCount > 0) {
            addPersonaFact(
                content = "Peak $source texting time for the user: $bandLabel.",
                traits = listOf("trait:texting-rhythm", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 3) Communication style — quick heuristic scan over the
        //    user's outbound text. Counts emoji density, abbrev
        //    presence ("lol", "btw", "rn", "u", "ur"), avg length.
        val style = classifyStyle(userMessages)
        if (style != null) {
            addPersonaFact(
                content = "User's $source communication style: $style.",
                traits = listOf("trait:style", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 4) Totals for transparency.
        val inboundCount = messages.count { !it.isFromUser }
        addPersonaFact(
            content = "Imported $source history: ${messages.size} messages (${userMessages.size} sent, $inboundCount received).",
            traits = listOf("trait:import-summary", "source:$source"),
            now = now,
        )?.let { written++ }

        // 5) Gemma deep-extraction pass over sampled outgoing messages.
        //    Only the USER's own messages — we never want to extract
        //    facts about the user's contacts ("Mom likes coffee") or
        //    misattribute someone else's preferences to the user.
        //
        //    Chunks are bounded by char count rather than message
        //    count — short SMS chunks better, long WhatsApp paragraphs
        //    chunk smaller. Each chunk gets one Gemma extract pass; we
        //    de-dup at write-time so recurring statements across chunks
        //    collapse into one vault record.
        //
        //    Skipped silently if Gemma isn't loaded (user hasn't
        //    downloaded the model yet). The heuristic pass above is
        //    always present, so the import is still useful in that
        //    state — Gemma upgrades the result, doesn't gate it.
        if (gemma.isReady() && userMessages.isNotEmpty()) {
            val gemmaWritten = runGemmaPass(source, userMessages, now)
            written += gemmaWritten
            Log.d(TAG, "gemma pass wrote $gemmaWritten persona records over ${userMessages.size} outgoing $source messages")
        } else if (!gemma.isReady()) {
            Log.d(TAG, "skipping gemma pass — model not loaded")
        }

        return Report(
            ok = true,
            recordsWritten = written,
            messagesAnalyzed = messages.size,
        )
    }

    /**
     * Build chunks of the user's outgoing messages and feed each to
     * Gemma. Returns the number of vault records written.
     *
     * Why not feed everything at once: Gemma's context window is small
     * (~2K char prompt cap in [GemmaExtractor.MAX_TRANSCRIPT_CHARS]),
     * and the extraction quality degrades on giant chunks. Multiple
     * smaller passes also give the model a chance to surface different
     * traits per chunk; if we batched everything the latest content
     * would dominate.
     *
     * Cap at [GEMMA_MAX_CHUNKS] chunks total so a 3000-message import
     * doesn't camp on Gemma for 20 minutes. We sample evenly across
     * the timeline so early + recent voice both get represented.
     */
    private suspend fun runGemmaPass(
        source: String,
        userMessages: List<MessageRecord>,
        now: Long,
    ): Int {
        val chunks = chunkMessages(userMessages)
        if (chunks.isEmpty()) return 0
        val sampled = if (chunks.size <= GEMMA_MAX_CHUNKS) chunks else sampleEvenly(chunks, GEMMA_MAX_CHUNKS)
        val collected = mutableListOf<String>()
        for ((idx, chunk) in sampled.withIndex()) {
            val transcript = buildTranscript(chunk)
            val result = runCatching { gemma.extractWithMood(transcript) }.getOrNull()
            if (result == null) {
                Log.w(TAG, "gemma chunk $idx returned null result (model error?)")
                continue
            }
            for (fact in result.facts) {
                if (fact.content.isNotBlank()) collected.add(fact.content.trim())
            }
        }
        if (collected.isEmpty()) return 0

        // De-dup case-insensitively. Gemma frequently surfaces the same
        // trait across chunks ("user works in tech", "the user is a
        // software engineer") — embedding-based dedup would be ideal
        // but case-insensitive contains() catches the bulk for free.
        val unique = mutableListOf<String>()
        for (line in collected) {
            val lower = line.lowercase()
            if (unique.none { it.lowercase().contains(lower) || lower.contains(it.lowercase()) }) {
                unique.add(line)
            }
        }

        var written = 0
        for (line in unique.take(GEMMA_MAX_RECORDS)) {
            addPersonaFact(
                content = line,
                traits = listOf("trait:gemma-extracted", "source:$source"),
                now = now,
            )?.let { written++ }
        }
        return written
    }

    /** Group adjacent messages into chunks bounded by [CHUNK_MAX_CHARS]. */
    private fun chunkMessages(msgs: List<MessageRecord>): List<List<MessageRecord>> {
        if (msgs.isEmpty()) return emptyList()
        val out = mutableListOf<MutableList<MessageRecord>>()
        var current = mutableListOf<MessageRecord>()
        var currentLen = 0
        for (m in msgs) {
            val len = m.text.length + 2
            if (currentLen + len > CHUNK_MAX_CHARS && current.isNotEmpty()) {
                out.add(current)
                current = mutableListOf()
                currentLen = 0
            }
            current.add(m)
            currentLen += len
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** Pick [count] evenly-spaced chunks across the timeline. */
    private fun <T> sampleEvenly(items: List<T>, count: Int): List<T> {
        if (count >= items.size) return items
        val step = items.size.toDouble() / count
        val out = mutableListOf<T>()
        var pos = 0.0
        repeat(count) {
            out.add(items[pos.toInt().coerceIn(0, items.size - 1)])
            pos += step
        }
        return out
    }

    /** Render a chunk as the transcript text passed to Gemma. */
    private fun buildTranscript(chunk: List<MessageRecord>): String =
        chunk.joinToString("\n") { it.text }.take(CHUNK_MAX_CHARS)

    private suspend fun addPersonaFact(
        content: String,
        traits: List<String>,
        now: Long,
    ): Boolean {
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = buildList {
            add("kind:persona")
            add("source:message-import")
            addAll(traits)
        }
        return runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "persona:message-import",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.85,
                now = now,
            )
        }.getOrElse {
            Log.w(TAG, "vault.add (persona/message-import) failed: ${it.message}")
            false
        }
    }

    private fun peakBand(hourCounts: IntArray): Pair<String, Int> {
        // Slide a 4-hour window across the 24-hour cycle (wrap-around)
        // and pick the one with the most messages.
        var bestStart = 0
        var bestSum = 0
        for (start in 0..23) {
            var sum = 0
            for (i in 0 until BAND_WIDTH) sum += hourCounts[(start + i) % 24]
            if (sum > bestSum) {
                bestSum = sum
                bestStart = start
            }
        }
        val endHour = (bestStart + BAND_WIDTH) % 24
        return "${formatHour(bestStart)}–${formatHour(endHour)}" to bestSum
    }

    private fun formatHour(h: Int): String = when (h) {
        0 -> "12am"
        12 -> "12pm"
        in 1..11 -> "${h}am"
        else -> "${h - 12}pm"
    }

    /** Quick style classification. Returns null if too few messages to classify. */
    private fun classifyStyle(userMessages: List<MessageRecord>): String? {
        if (userMessages.size < STYLE_MIN_MESSAGES) return null
        var emojiCount = 0
        var abbrevCount = 0
        var totalLen = 0
        for (m in userMessages) {
            totalLen += m.text.length
            // Emoji: any codepoint in the common emoji ranges
            var i = 0
            while (i < m.text.length) {
                val cp = m.text.codePointAt(i)
                if (cp in 0x1F000..0x1FFFF || cp in 0x2600..0x27BF) emojiCount++
                i += Character.charCount(cp)
            }
            // Abbreviations: token-bound match against a small set
            for (abbrev in ABBREVIATIONS) {
                if (TOKEN.matcher(m.text).results()
                        .anyMatch { it.group().equals(abbrev, ignoreCase = true) }
                ) abbrevCount++
            }
        }
        val avgLen = totalLen.toFloat() / userMessages.size
        val emojiRate = emojiCount.toFloat() / userMessages.size
        val abbrevRate = abbrevCount.toFloat() / userMessages.size
        return when {
            emojiRate >= 0.5f && abbrevRate >= 0.3f -> "casual, emoji + abbreviation heavy (avg ${avgLen.toInt()} chars/msg)"
            emojiRate >= 0.5f -> "casual, emoji-rich (avg ${avgLen.toInt()} chars/msg)"
            abbrevRate >= 0.3f -> "casual, abbreviation-heavy (avg ${avgLen.toInt()} chars/msg)"
            avgLen >= 100f -> "long-form, formal (avg ${avgLen.toInt()} chars/msg)"
            avgLen >= 50f -> "moderate length, conversational (avg ${avgLen.toInt()} chars/msg)"
            else -> "terse, brief (avg ${avgLen.toInt()} chars/msg)"
        }
    }

    companion object {
        private const val TAG = "Mythara/MsgImport"
        private const val TOP_CONTACTS = 5
        private const val BAND_WIDTH = 4
        private const val STYLE_MIN_MESSAGES = 50

        // Gemma pass tuning.
        // CHUNK_MAX_CHARS sits below GemmaExtractor.MAX_TRANSCRIPT_CHARS
        // (2_000) so the prompt template + transcript stay under the
        // model's context window. GEMMA_MAX_CHUNKS bounds total inference
        // cost; ~10s per chunk on CPU means a 12-chunk import is ~2 min.
        // GEMMA_MAX_RECORDS caps the persona records we'll insert from a
        // single import so a chatty model can't flood the vault.
        private const val CHUNK_MAX_CHARS = 1_600
        private const val GEMMA_MAX_CHUNKS = 12
        private const val GEMMA_MAX_RECORDS = 30
        private val ABBREVIATIONS = setOf(
            "lol", "btw", "rn", "u", "ur", "tbh", "imo", "imho",
            "omw", "thx", "thnx", "k", "kk", "np", "ttyl", "brb",
        )
        // Lazy Java regex — Kotlin Regex doesn't have a streaming
        // results() API. Pattern is "word-character runs".
        private val TOKEN = java.util.regex.Pattern.compile("""\b[\w']+\b""")
    }
}
