package com.mythara.music

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an agent reply text into a sequence of [Motif]s for
 * [MusicToneEngine] to play. v7+ rewrite — Music Mode is now a
 * proper constructed-language renderer instead of a sparse
 * content-word stutter.
 *
 * Pipeline:
 *   1. Tokenise on character class (word vs. non-word).
 *   2. For each word:
 *      - If it's a known function word (see [MusicGrammar.FUNCTION_WORDS])
 *        → emit a single-note "particle motif" at the category's pitch.
 *        The listener feels the syntax (connectives, copulas, pronouns
 *        all sound like themselves) without needing to learn 50
 *        distinct word motifs for grammatical scaffolding.
 *      - Else strip a known morphological suffix (-ing/-ed/-s/-ly/...)
 *        → emit the STEM's content-word motif plus a one-note "morphology
 *        marker" tone. So `walking` = motif(`walk`) + ing-tone;
 *        `dogs` = motif(`dog`) + plural-tone. Tense + plurality become
 *        audible without bloating the vocabulary with every inflection.
 *      - Else (bare content word) → emit the motif as today.
 *   3. Non-word runs (spaces, punctuation) are returned with
 *      `motif == null` so the renderer paints them in the default
 *      colour.
 *
 * The vocabulary store is never written to with particle or suffix
 * tones — those are deterministic, shared, and computed at render
 * time. Only content-word stems mint entries in [MusicVocabulary].
 *
 * [MAX_MOTIFS_PER_REPLY] is now generous (was 8 — that capped most
 * sentences at "subject + verb" and felt broken). At 96 motifs every
 * realistic agent reply voices end-to-end; only pathologically long
 * outputs hit the cap.
 */
@Singleton
class MusicReplyEncoder @Inject constructor(
    private val vocabulary: MusicVocabulary,
) {

    /** A motif plus the token it represents. */
    data class TokenMotif(val token: String, val motif: Motif)

    /** A piece of the reply text laid out for display. Words that
     *  carry a motif have one; non-word runs (whitespace,
     *  punctuation) come back with `motif == null` so the renderer
     *  paints them in the default body colour. */
    data class Segment(
        val text: String,
        val motif: Motif?,
    )

    /** Compose the motifs that will play through [MusicToneEngine].
     *  Walks the text in the same pass as [renderSegments] to keep
     *  the audible sequence and the on-screen colours in perfect
     *  lockstep — what the user hears at index N is exactly what
     *  the bubble highlights at index N. */
    suspend fun encode(text: String): List<TokenMotif> {
        val out = ArrayList<TokenMotif>()
        var produced = 0
        forEachContentToken(text) { displayWord ->
            if (produced >= MAX_MOTIFS_PER_REPLY) return@forEachContentToken
            val motif = motifForWord(displayWord) ?: return@forEachContentToken
            out.add(TokenMotif(displayWord, motif))
            produced++
        }
        return out
    }

    /**
     * Build the visual segment list. Identical lookup logic to
     * [encode], but returns the whole input text in display order
     * (every word + every space + every punctuation mark) so the
     * chat bubble can paint each word in its motif colour while
     * preserving the original layout exactly.
     */
    suspend fun renderSegments(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Segment>()
        var motifsAssigned = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val start = i
            val ch = text[i]
            if (ch.isLetterOrDigit()) {
                while (i < n && text[i].isLetterOrDigit()) i++
                val word = text.substring(start, i)
                val motif = if (motifsAssigned < MAX_MOTIFS_PER_REPLY) {
                    motifForWord(word).also { if (it != null) motifsAssigned++ }
                } else null
                out.add(Segment(text = word, motif = motif))
            } else {
                while (i < n && !text[i].isLetterOrDigit()) i++
                out.add(Segment(text = text.substring(start, i), motif = null))
            }
        }
        return out
    }

    /** Per-token motif lookup. The shared core between [encode] and
     *  [renderSegments]; keeping both paths through this one function
     *  guarantees the audio sequence and the visual segment list
     *  never drift out of sync.
     *
     *  Returns null only for inputs that don't normalise to anything
     *  (e.g. pure-punctuation token) — callers treat that as "no
     *  motif" and the segment renders in the default body colour. */
    private suspend fun motifForWord(word: String): Motif? {
        val key = word.lowercase()
        if (key.isEmpty()) return null

        // Function-word fast path — single-note particle motif at
        // the category's deterministic pitch. Never enters the
        // vocabulary store.
        MusicGrammar.FUNCTION_WORDS[key]?.let { particle ->
            return Motif(notes = listOf(particle.hz))
        }

        // Morphology peel — stem motif + suffix marker. Only fires
        // when the word ends with a known suffix AND the stem is
        // long enough to stand on its own as a content word.
        MusicGrammar.peelSuffix(key)?.let { (stem, suffixHz) ->
            val stemMotif = vocabulary.motifFor(stem)
            return stemMotif.copy(notes = stemMotif.notes + suffixHz)
        }

        // Bare content word — direct vocabulary lookup.
        return vocabulary.motifFor(key)
    }

    /** Walk the input text, calling [body] once per content word in
     *  display order. Used by [encode] when assembling the audio
     *  sequence so the iteration shape matches [renderSegments]'s
     *  text walk exactly. */
    private inline fun forEachContentToken(text: String, body: (String) -> Unit) {
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch.isLetterOrDigit()) {
                val start = i
                while (i < n && text[i].isLetterOrDigit()) i++
                body(text.substring(start, i))
            } else {
                i++
            }
        }
    }

    companion object {
        /** Hard cap on motifs per reply. Used to be 8 — that capped
         *  most replies at the first half-sentence and felt broken.
         *  At 96 motifs every realistic agent reply voices end-to-
         *  end (a 96-word reply is ~150 spoken words; agent replies
         *  in chat are typically much shorter than that). Pathological
         *  multi-paragraph outputs still cap so the audio doesn't
         *  monopolise the speaker for minutes. */
        const val MAX_MOTIFS_PER_REPLY = 96
    }
}
