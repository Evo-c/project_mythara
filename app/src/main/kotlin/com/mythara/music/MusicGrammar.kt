package com.mythara.music

/**
 * The grammatical layer of Music Mode — turns Music Mode from a
 * sparse content-word stutter into a real constructed-language: a
 * spoken sentence renders as a continuous OM-harmonic stream where
 * function words have their own deterministic "particle" tones
 * (so the listener feels the syntax) and morphological suffixes
 * (-ing, -ed, -s, -ly, ...) append a brief "morphology marker" to
 * the stem motif (so tense, plurality, and adverbiality are
 * audible).
 *
 * Two design rules drive the choices below:
 *
 *  1. **Every grammatical category gets ONE consistent pitch.**
 *     The user learns "the OM root = a connective" the same way
 *     they learn "/o/ in a content word = a deep tone". The
 *     vocabulary itself is unique per-word; the grammar layer is
 *     deliberately shared — that's what makes the syntax learnable.
 *
 *  2. **Particles stay short, content words stay long.** The
 *     engine plays everything at the same per-note duration, but
 *     particles are single-note motifs (one short OM tone each)
 *     while content words are multi-note phrases. The audible
 *     contrast is "one syllable function word vs. polysyllabic
 *     content word" — the same rhythm a real language has.
 *
 * No changes to the [Motif] serialization or [MusicVocabulary]
 * persistence — particles + suffix markers are generated at
 * render time only. They never enter the vocabulary store. The
 * dictionary stays purely the user's content-word lexicon.
 */
object MusicGrammar {

    /** Functional categories. Each maps to a single OM-harmonic pitch
     *  chosen to feel acoustically right for the category:
     *
     *    LINKER         136.1 Hz   OM root — connects clauses, the
     *                              "ground" pitch
     *    COPULA         272.2 Hz   octave above OM — "to be", the
     *                              identity tone, slightly above the
     *                              root because it asserts
     *    DIRECTIONAL    544.4 Hz   4th harmonic, warm — spatial
     *                              + relational particles
     *    DEMONSTRATIVE  408.3 Hz   3rd harmonic, resonant — points
     *                              to something specific
     *    PRONOUN        680.5 Hz   5th harmonic, bright — refers
     *                              to a person/thing in focus
     *    QUANTIFIER     816.6 Hz   6th harmonic — counts and
     *                              degrees
     *    NEGATOR        952.7 Hz   7th harmonic, edge-bright — the
     *                              "no/not" cuts through
     *    INTERROGATIVE  1088.8 Hz  8th harmonic, three octaves up —
     *                              rising-question feel
     *    DETERMINER     408.3 Hz   reuses the demonstrative pitch
     *                              ("a/the" point to a noun the same
     *                              way "this/that" do, just less
     *                              specifically)
     */
    enum class Particle(val hz: Float, val label: String) {
        LINKER(136.1f, "linker"),
        COPULA(272.2f, "copula"),
        DIRECTIONAL(544.4f, "directional"),
        DEMONSTRATIVE(408.3f, "demonstrative"),
        PRONOUN(680.5f, "pronoun"),
        QUANTIFIER(816.6f, "quantifier"),
        NEGATOR(952.7f, "negator"),
        INTERROGATIVE(1088.8f, "interrogative"),
        DETERMINER(408.3f, "determiner"),
    }

    /** Lookup table from a (lowercased) function word → its
     *  particle category. Anything not in the table is treated as
     *  a content word and routed through [MusicVocabulary]. */
    val FUNCTION_WORDS: Map<String, Particle> = buildMap {
        // Linkers / connectives — the OM-root tone, ground the sentence.
        for (w in listOf(
            "and", "or", "but", "if", "then", "because", "so",
            "while", "though", "although", "yet", "as",
        )) put(w, Particle.LINKER)

        // Copulas — the octave above OM, "is" assertion tone.
        for (w in listOf("is", "are", "was", "were", "be", "been", "am", "being")) {
            put(w, Particle.COPULA)
        }

        // Directionals / prepositions — warm 4th, spatial+relational.
        for (w in listOf(
            "to", "from", "in", "on", "at", "with", "by", "for", "of",
            "about", "into", "onto", "over", "under", "through", "between",
            "across", "behind", "above", "below", "near", "around",
        )) put(w, Particle.DIRECTIONAL)

        // Demonstratives — 3rd harmonic, point at something specific.
        for (w in listOf("this", "that", "these", "those", "here", "there")) {
            put(w, Particle.DEMONSTRATIVE)
        }

        // Pronouns — bright 5th, refer to a participant.
        for (w in listOf(
            "i", "me", "my", "mine", "myself",
            "you", "your", "yours", "yourself",
            "we", "us", "our", "ours", "ourselves",
            "they", "them", "their", "theirs", "themselves",
            "he", "him", "his", "himself",
            "she", "her", "hers", "herself",
            "it", "its", "itself",
        )) put(w, Particle.PRONOUN)

        // Quantifiers — 6th harmonic, degrees and counts.
        for (w in listOf(
            "some", "all", "any", "many", "few", "much", "more", "most",
            "less", "least", "every", "each", "both", "either", "neither",
            "several", "one", "two", "three", "four", "five",
        )) put(w, Particle.QUANTIFIER)

        // Negators — 7th harmonic, edge that cuts.
        for (w in listOf("no", "not", "never", "none", "nothing", "nobody", "nowhere")) {
            put(w, Particle.NEGATOR)
        }

        // Interrogatives — 8th, rising-question pitch.
        for (w in listOf("who", "what", "when", "where", "why", "how", "which", "whose")) {
            put(w, Particle.INTERROGATIVE)
        }

        // Determiners — same pitch as demonstratives but mild.
        for (w in listOf("a", "an", "the")) put(w, Particle.DETERMINER)
    }

    /** Morphological suffix → appended tone. Each marker is ONE
     *  extra note at the listed pitch, glued to the end of the
     *  stem's content-word motif. So "walking" sounds like
     *  /motif-of-walk/ + [-ing tone]; "walked" sounds like
     *  /motif-of-walk/ + [-ed tone]. The listener learns "this
     *  little tail = past tense" naturally.
     *
     *  Pitch choices map the morpheme's *meaning* to a harmonic
     *  with matching feel:
     *
     *    -ing    680.5 Hz   5th, bright — ongoing / continuous
     *    -ed     136.1 Hz   OM root — past, settled, deep
     *    -s      1088.8 Hz  8th — plural / sharp
     *    -es     1088.8 Hz  8th — plural / sharp
     *    -ly     952.7 Hz   7th — adverbial, edge-bright
     *    -tion   544.4 Hz   4th, warm — nominalisation
     *    -sion   544.4 Hz   4th, warm — nominalisation
     *    -er     816.6 Hz   6th — comparative / agent
     *    -est    1224.9 Hz  9th — superlative / topmost
     *    -ment   408.3 Hz   3rd, resonant — abstract nominalisation
     *    -ness   272.2 Hz   2nd — quality nominalisation
     *    -less   952.7 Hz   7th — privative, edge-cuts
     *    -ful    544.4 Hz   4th — augmentative, warm-full
     */
    val SUFFIXES: List<Pair<String, Float>> = listOf(
        // Order matters: longer suffixes first so "running" matches
        // "-ing" before any 2-letter suffix could catch the tail.
        "tion" to 544.4f,
        "sion" to 544.4f,
        "ment" to 408.3f,
        "ness" to 272.2f,
        "less" to 952.7f,
        "ful" to 544.4f,
        "ing" to 680.5f,
        "est" to 1224.9f,
        "ly" to 952.7f,
        "ed" to 136.1f,
        "er" to 816.6f,
        "es" to 1088.8f,
        "s" to 1088.8f,
    )

    /** Try to peel a known suffix off the end of [word]. Returns the
     *  (stem, suffix-pitch) pair when a match is found, else null.
     *  Requires a stem of ≥ 3 chars so we don't shred short words
     *  (e.g. "is" wouldn't be reduced to "i" + "-s").
     *
     *  Stems retain their original spelling; we don't reverse English
     *  orthographic rules (the "y → ies" of "babies" stays as "babi"
     *  + "es" — phonetically that's fine because vowel-derived motif
     *  generation already uses the stem's vowels). */
    fun peelSuffix(word: String): Pair<String, Float>? {
        if (word.length < 5) return null
        for ((suffix, pitch) in SUFFIXES) {
            if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
                return word.substring(0, word.length - suffix.length) to pitch
            }
        }
        return null
    }
}
