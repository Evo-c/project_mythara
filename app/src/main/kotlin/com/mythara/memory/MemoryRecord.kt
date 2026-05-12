package com.mythara.memory

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * One memory entry. Same shape across all four tiers
 * ([Tier.Working] / [Tier.Episodic] / [Tier.Semantic] / [Tier.Procedural])
 * because the consolidation pipeline (M8.3+) re-classifies records as
 * they age — promoting reinforced working observations into semantic
 * facts, etc. Having a single shape means promotion is a copy with a
 * tier flip, not a schema migration.
 *
 * Short keys on the wire — bytes matter when we're writing JSONL files
 * over a metered connection from a phone. The mapping is documented
 * here so the GitHub repo stays readable:
 *
 *   id       — ULID-style "<base36-millis>-<random8>" (28 chars, sortable by time)
 *   t        — epoch millis
 *   tier     — "w" / "e" / "s" / "p"  (Working / Episodic / Semantic / Procedural)
 *   src      — provenance: "chat" | "observe" | "tool:<name>" | "growth:<kind>"
 *   conf     — confidence 0..1; default 1.0
 *   facets   — dimension:value strings: "topic:python", "kind:preference", "person:ankur"
 *   content  — short text payload (caps suggested upstream: ~600 chars working, ~200 semantic)
 *   sha      — SHA-256 prefix (24 chars) of `content` — dedup key across syncs
 *   ref      — optional back-link to source event (e.g. "msg:42", "obs:abc")
 *   seen     — reinforcement counter; bumped each time the same sha is observed
 *
 * Mirrors the field discipline of github.com/rohitg00/agentmemory, adapted
 * for mobile: no embeddings stored on-device, no graph relations on disk
 * (those reconstruct from `facets` + `ref`), short keys throughout.
 */
@Serializable
data class MemoryRecord(
    val id: String,
    val t: Long,
    val tier: String,
    val src: String,
    val conf: Double = 1.0,
    val facets: List<String> = emptyList(),
    val content: String,
    val sha: String,
    val ref: String? = null,
    val seen: Int = 1,
) {
    companion object {
        /** Compact, time-sortable ID. Not a real ULID — close enough for our scale. */
        fun newId(now: Long = System.currentTimeMillis()): String {
            val ts = now.toString(36).padStart(9, '0')   // ~73 years coverage in 9 chars
            val rnd = UUID.randomUUID().toString().replace("-", "").take(12)
            return "$ts-$rnd"
        }

        fun shaHash(content: String): String {
            val md = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return md.joinToString("") { "%02x".format(it) }.take(24)
        }

        fun working(
            content: String,
            src: String,
            facets: List<String> = emptyList(),
            ref: String? = null,
            conf: Double = 1.0,
            now: Long = System.currentTimeMillis(),
        ): MemoryRecord = MemoryRecord(
            id = newId(now), t = now, tier = Tier.Working.code,
            src = src, conf = conf, facets = facets,
            content = content, sha = shaHash(content), ref = ref,
        )

        fun semantic(
            content: String,
            src: String,
            facets: List<String> = emptyList(),
            ref: String? = null,
            conf: Double = 1.0,
            seen: Int = 1,
            now: Long = System.currentTimeMillis(),
        ): MemoryRecord = MemoryRecord(
            id = newId(now), t = now, tier = Tier.Semantic.code,
            src = src, conf = conf, facets = facets,
            content = content, sha = shaHash(content), ref = ref, seen = seen,
        )
    }
}

/**
 * Lifecycle tiers, mirrored from agentmemory's working/episodic/semantic/procedural
 * model. Single-letter codes on the wire to keep records small.
 *
 * - [Working]    raw, freshly captured observations (chat turn, observe snippet)
 * - [Episodic]   compressed session summary ("what happened this week")
 * - [Semantic]   extracted durable facts/preferences ("what I know about you")
 * - [Procedural] action patterns / workflows (how to do X — emerges from
 *                repeated tool sequences, populated by M8.4+)
 */
enum class Tier(val code: String, val dir: String) {
    Working("w",    "working"),
    Episodic("e",   "episodic"),
    Semantic("s",   "semantic"),
    Procedural("p", "procedural");

    companion object {
        fun fromCode(c: String): Tier? = entries.firstOrNull { it.code == c }
    }
}
