package com.mythara.secret.observe.extract

/**
 * Detects explicit "talk to Lumi" prefixes in Observe transcripts and
 * extracts the tail as a deliberate user note.
 *
 *   "Lumi, remember that the new wifi password is xyz"
 *     → note: "remember that the new wifi password is xyz"
 *
 *   "Hey Lumi note this down — meeting moved to 4pm"
 *     → note: "note this down meeting moved to 4pm"
 *
 * The match is anchored at start-of-utterance only (we don't want
 * "I told Lumi about the bug" to fire). Vosk transcripts are
 * lowercase by default, but the regex is case-insensitive anyway.
 *
 * False-positive surface area is low: Mythara is "Lumi" specifically
 * because the syllable count + vowel pattern is rare in English
 * conversation. If the user starts a sentence with "Lumi" they almost
 * certainly mean the assistant.
 *
 * Pairs with M8.3a's wake-word listener: same trigger phrase, different
 * mode. The wake word opens the chat surface; the note detector lets
 * Observe-mode quietly capture asks-while-passing without needing the
 * user to switch contexts.
 */
object LumiNoteDetector {

    /**
     * Returns the note text (with the "Lumi" prefix stripped) if the
     * transcript begins with a Lumi address, else null.
     *
     * Recognised prefixes:
     *   - "Lumi[,/:] <note>"                  (bare address)
     *   - "Hey Lumi[,/:] <note>"
     *   - "Hi Lumi[,/:] <note>"
     *   - "Okay Lumi[,/:] <note>"
     *   - "Hello Lumi[,/:] <note>"
     *   - Same forms with no comma/colon, just whitespace
     *   - "Lumi please <note>" / "Lumi remember <note>" (imperatives)
     *
     * Also tolerates common Vosk mishears for the proper noun
     * ("loomi", "lumy", "loomie") since "Lumi" is OOV for the en-us
     * small model and likely to get transcribed phonetically.
     */
    fun detect(transcript: String): String? {
        val s = transcript.trim()
        if (s.isEmpty()) return null
        val m = PREFIX_RE.find(s) ?: return null
        return s.substring(m.range.last + 1).trim().ifBlank { null }
    }

    /**
     * Pattern, in order:
     *   - one of two alternations covering Vosk-en-us mishears of
     *     the proper noun "Lumi" (which is out-of-vocab and gets
     *     hallucinated as nearby phonemes):
     *
     *     a) "<opener> me" — Vosk drops the L and renders the
     *        remaining "-uh-mee" as just "me", typically with a short
     *        opener like `a`, `hey`, `hello`, `hi`, `the` from the
     *        original "Hey Lumi". Real samples captured in field test:
     *          "a me" (was "Hey Lumi")
     *          "hello me what time is it" (was "Hey Lumi what time is it")
     *
     *     b) An L-vowel-token whose phonemes are close to "loomy":
     *        `lumi / loomi / loomy / lumey / leumi / lumy / lumie /
     *        loomie / lou me / lou mi`, with an optional standard
     *        opener. Real samples:
     *          "leumi hello" (was "Lumi hello")
     *
     *   - optional connector words (please / can you / remember /
     *     note / jot down) consumed by the prefix so the returned
     *     tail is the actual user query.
     *   - optional `,` `:` `-` or whitespace before the query.
     *
     * Trade-off: pattern (a) makes the regex broader and admits some
     * legitimate "hey me ..." utterances as Lumi commands. Acceptable
     * because that phrasing is unusual in natural speech, and users
     * who say "hey me" deliberately probably want to address the
     * assistant anyway.
     */
    private val PREFIX_RE = Regex(
        pattern = """^\s*(?:(?:hey|hi|hello|okay|ok|yo|a|the)\s+me|(?:hey\s+|hi\s+|hello\s+|okay\s+|ok\s+|yo\s+)?(?:lumi|loomi|loomy|lumey|leumi|lumy|lumie|loomie|lou\s+me|lou\s+mi))\b[\s,:\-]*(?:please\s+|can\s+you\s+|could\s+you\s+|remember\s+|note\s+|jot\s+down\s+)?""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
}
