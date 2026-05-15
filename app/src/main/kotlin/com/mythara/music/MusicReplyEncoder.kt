package com.mythara.music

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an agent reply text into a sequence of [Motif]s for
 * [MusicToneEngine] to play. Tokenises on whitespace, filters
 * stopwords (so a long reply doesn't take 30 s of tones — the user
 * can infer "the / a / is" from context once they know the content
 * words), then asks [MusicVocabulary] for a motif per remaining
 * token.
 *
 * Capped at [MAX_MOTIFS_PER_REPLY] motifs so even verbose replies
 * fit in a few seconds of audio. After the cap, encoding silently
 * stops — the underlying text is still shown, just not fully
 * "voiced" in tones.
 */
@Singleton
class MusicReplyEncoder @Inject constructor(
    private val vocabulary: MusicVocabulary,
) {

    /** A motif plus the token it represents. */
    data class TokenMotif(val token: String, val motif: Motif)

    /** A piece of the reply text laid out for display. Words that
     *  carry a motif have one; whitespace / punctuation / filtered
     *  stopwords are returned with `motif == null` so the renderer
     *  can paint them in the default colour. */
    data class Segment(
        val text: String,
        val motif: Motif?,
    )

    suspend fun encode(text: String): List<TokenMotif> {
        val raw = text.split(WHITESPACE).asSequence()
            .map { it.lowercase().filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() && it !in STOPWORDS }
            .take(MAX_MOTIFS_PER_REPLY)
            .toList()
        return raw.map { tok ->
            TokenMotif(token = tok, motif = vocabulary.motifFor(tok))
        }
    }

    /**
     * Same vocabulary lookup as [encode], but returns the WHOLE
     * input text broken into ordered [Segment]s — every word, every
     * space, every punctuation mark in its original position. The
     * chat bubble uses this to paint each word in the colour of its
     * motif while preserving the original text exactly.
     *
     * The same [MAX_MOTIFS_PER_REPLY] cap applies: once we've
     * minted that many distinct motifs, subsequent words are
     * returned with `motif == null` (they'll render in the default
     * colour but the audio playback won't go on forever).
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
                // Walk the word.
                while (i < n && text[i].isLetterOrDigit()) i++
                val word = text.substring(start, i)
                val key = word.lowercase()
                val motif = if (key !in STOPWORDS && motifsAssigned < MAX_MOTIFS_PER_REPLY) {
                    motifsAssigned++
                    vocabulary.motifFor(key)
                } else null
                out.add(Segment(text = word, motif = motif))
            } else {
                // Walk a run of non-word chars (spaces + punctuation)
                while (i < n && !text[i].isLetterOrDigit()) i++
                out.add(Segment(text = text.substring(start, i), motif = null))
            }
        }
        return out
    }

    companion object {
        /** Hard cap on motifs played per reply. Stops a verbose model
         *  reply from monopolising the audio stream — anything beyond
         *  this is still in the text, just not voiced. */
        const val MAX_MOTIFS_PER_REPLY = 8

        private val WHITESPACE = Regex("\\s+")

        /** Very short stopword list — the most common function words.
         *  Filtered out so a 30-word reply doesn't become 30 motifs;
         *  the content words carry the meaning, and the user fills in
         *  function words from grammatical context. Kept tiny so the
         *  language stays "the user's" rather than "tuned by NLP
         *  heuristics." */
        private val STOPWORDS: Set<String> = setOf(
            "a", "an", "the", "and", "or", "but", "if", "of", "on", "in",
            "at", "to", "for", "is", "are", "was", "were", "be", "been",
            "i", "you", "we", "they", "it", "this", "that", "these",
            "those", "my", "your", "our", "their", "with", "as", "by",
            "from", "up", "out", "do", "does", "did", "so", "not",
        )
    }
}
