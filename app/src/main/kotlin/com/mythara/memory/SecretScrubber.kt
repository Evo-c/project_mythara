package com.mythara.memory

/**
 * Belt-and-suspenders: scrub anything that *looks like* a secret out of
 * memory record content before we ship it to GitHub.
 *
 * The repo is private and the upstream code paths already make sure
 * neither the MiniMax key nor the GitHub PAT are ever passed into
 * memory records — but if a user pastes a key into a chat message,
 * for example, we should not let it ride into a sync.
 *
 * Patterns covered:
 *   - GitHub PAT classic / fine-grained / app installation
 *   - OpenAI-style keys (`sk-...`)
 *   - Bearer JWTs (the MiniMax API key shape: `eyJ...`)
 *   - AWS access key IDs (`AKIA...`)
 *   - Slack/Stripe-shape webhook tokens (defensive)
 *   - Generic 32+ char hex blobs (likely a hash or secret)
 *
 * Replaces matched runs with `[redacted]`. Best-effort: not a substitute
 * for keeping keys out of message content in the first place.
 */
object SecretScrubber {

    private val PATTERNS: List<Regex> = listOf(
        // GitHub
        Regex("""\bghp_[A-Za-z0-9]{30,}\b"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{60,}\b"""),
        Regex("""\bghs_[A-Za-z0-9]{30,}\b"""),
        Regex("""\bgho_[A-Za-z0-9]{30,}\b"""),
        // OpenAI / Anthropic / generic provider keys
        Regex("""\bsk-(?:proj-|live-|test-)?[A-Za-z0-9_-]{20,}\b"""),
        Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}\b"""),
        // MiniMax / general JWTs (eyJ-prefixed)
        Regex("""\beyJ[A-Za-z0-9_.\-]{60,}\b"""),
        // AWS
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        // Slack
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}\b"""),
        // Stripe
        Regex("""\bsk_(?:live|test)_[A-Za-z0-9]{20,}\b"""),
        Regex("""\bpk_(?:live|test)_[A-Za-z0-9]{20,}\b"""),
        // Long hex blobs (likely hashes, tokens, or secrets)
        Regex("""\b[a-fA-F0-9]{40,}\b"""),
    )

    fun scrub(input: String): String {
        var s = input
        for (p in PATTERNS) {
            s = p.replace(s, "[redacted]")
        }
        return s
    }
}
