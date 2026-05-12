# Self-organizing learning loop (M8 + M8.5)

Mythara doesn't just observe — it *grows*. The Observe pipeline captures
audio, extracts structured learnings, and persists them. The
**self-organizing learning loop** sits on top of that vault and turns
accumulated learnings into:

1. **Refined system context** — the agent's system prompt gets richer
   over time with high-confidence facts about the user.
2. **Self-organized vault** — learnings cluster by topic; high-reinforced
   facts get promoted to "core" and ride along every turn, peripheral
   facts stay searchable but out of the prompt.
3. **Scheduled growth tasks** — Mythara generates its own to-do list:
   "research X because the user mentioned it 3× this week", "summarise
   last week's themes", "check if model preferences should change."
4. **Capability tuning** — tracks which tools are used (and which fail);
   biases the model toward useful ones, demotes the rest.

The whole loop runs **on-device, never sending Observe data off the
phone**. The only network calls are the deliberate self-directed agent
turns (which the user can audit in the Activity log).

---

## Components

```
┌──────────────────┐
│  Observe layer   │  M8: Vosk ASR + Gemma extractor + RawDataPurger
│ (audio → text →  │  → emits ObservationEvent → LearningVault
│   learnings)     │
└─────────┬────────┘
          ▼
┌──────────────────┐
│ LearningVault    │  M8: SQLCipher-encrypted, schema:
│ (canonical store)│  id, ts, topic, content, confidence,
└─────────┬────────┘  source, last_seen, times_reinforced
          ▼
┌──────────────────┐
│ SelfOrganizer    │  Periodic: cluster by embedding similarity,
│ (cluster + tune) │  promote high-reinforced → CoreFacts,
└─────────┬────────┘  demote stale, dedupe near-duplicates
          ▼
┌──────────────────┐
│ GoalGenerator    │  Periodic: from CoreFacts + recent learnings,
│ (self-prompts)   │  generate self-directed agent prompts
└─────────┬────────┘  (e.g., "summarise this week's themes")
          ▼
┌──────────────────┐
│ GrowthScheduler  │  WorkManager-driven cron:
│ (cadence)        │   - nightly @ 3am while charging
└─────────┬────────┘   - weekly @ Sunday 10am
          ▼              - on-demand from Secret panel
┌──────────────────┐
│ GrowthAgent      │  AgentLoop variant: runs without UI,
│ (autonomous run) │  consumes a self-prompt, full tool access,
└─────────┬────────┘  results stored back into LearningVault
          ▼
┌──────────────────┐
│ CapabilityTuner  │  Tracks per-tool: invocation_count, success_rate,
│ (tool tuning)    │  avg_duration. Adjusts tool descriptions in the
└──────────────────┘  schema to bias the model toward valuable tools.
```

---

## Self-organization rules

The vault never grows without bound. Each nightly compaction:

1. **Reinforcement** — a new learning that semantically matches an
   existing one (cosine ≥ 0.85 on embeddings) increments
   `times_reinforced` on the canonical row and discards the duplicate.
2. **Promotion** — when `times_reinforced ≥ 5` AND `confidence ≥ 0.7`,
   the learning is marked `core = true`. Core facts are *always* in the
   system prompt for chat turns.
3. **Demotion** — `last_seen_ts` older than 90 days AND `times_reinforced < 2`
   → marked `archived = true`. Archived facts are searchable via Vault
   Browser but not surfaced in prompts.
4. **Hard purge** — archived facts older than 1 year, with no
   reinforcement, are deleted entirely. The vault stays roughly bounded
   at ~1000 active learnings, ~5000 archived, indefinite for core.

---

## Scheduled growth tasks

The `GrowthScheduler` registers WorkManager jobs at two cadences:

### Nightly (3am local, while charging)
Single WorkManager `OneTimeWorkRequest` rescheduled every 24h. Constraints:
`RequiresCharging` + `RequiresBatteryNotLow`. Job sequence:

1. `RawDataPurger.sweep()` — delete audio > 60s old, transcripts > 24h.
2. `SelfOrganizer.compactDeltaSinceLastRun()` — dedupe + reinforce + promote/demote.
3. `CapabilityTuner.recomputeSchema()` — refresh tool descriptions
   based on usage.
4. `GoalGenerator.daily()` — picks one prompt from a templated set,
   substituting in recent themes:
   - "What did I learn yesterday? Summarise in 3 bullets."
   - "Which topic did the user mention most in the last 24h?"
   - "Is there a recurring schedule pattern I should remember?"
5. `GrowthAgent.run(prompt)` — autonomous turn, results stored as
   new learnings with `source=growth_nightly`.

### Weekly (Sunday 10am)
Bigger reflection. Constraints: `RequiresBatteryNotLow`. Job sequence:

1. `SelfOrganizer.weeklyReview()` — emits a "themes of the week" digest
   from the top-K most-reinforced learnings.
2. `GoalGenerator.weekly()` — generates 1–3 longer-running self-prompts:
   - "Research a topic the user is curious about but hasn't asked
     directly about — pick one from recent learnings."
   - "Identify gaps in my knowledge about the user's daily routine."
   - "Suggest a new capability I should request the user enable."
3. `GrowthAgent.run(each prompt)` — autonomous turns, results stored.
4. The digest gets surfaced as a "this week" notification when the user
   next opens Mythara, with a "review learnings" deep-link to the Vault
   Browser.

### On-demand
The Secret Settings panel has a "Run growth now" button that triggers
an immediate `GrowthScheduler.fireNow()` for testing + impatient users.

---

## Capability tuning

`ToolUsageTracker` records (in plain DataStore, not the encrypted vault):

```kotlin
data class ToolStats(
    val name: String,
    val invocations: Int,
    val successes: Int,
    val failures: Int,
    val avgDurationMs: Long,
    val lastUsedTs: Long,
)
```

`CapabilityTuner.recomputeSchema()` adjusts the `Tool.description`
strings that go to MiniMax in two ways:

1. **Hot tools** (high success, frequent use): description gets a hint
   like "Frequently useful — call this when the user asks about X".
2. **Cold tools** (rarely used, often fails): description gets dampened
   — "Only call when explicitly needed; prefer alternatives."

The model receives these tuned schemas on every turn. Over time, the
agent's tool-call distribution shifts toward what's been working for
the user.

---

## Privacy invariants (non-negotiable)

These are enforced by code, asserted by CI tests:

- **Observe audio + transcripts never leave the device.** All
  extraction happens via Vosk + Gemma INT4 locally. The
  `NetworkInterceptor` blacklists any request whose body contains
  observed-text markers.
- **Growth-agent turns are auditable.** Each autonomous turn writes
  a full transcript to the Activity log (a read-only view in the
  Secret panel). User can see exactly what self-prompts ran, when,
  and what the model replied with.
- **"Forget everything" cascades.** The button wipes the vault,
  growth job history, capability stats, audio/transcript scratch.
  One transaction, no half-states.
- **Growth jobs are pausable.** The Secret panel has a "Pause growth"
  toggle that cancels all scheduled WorkRequests and prevents new
  ones from registering until re-enabled.
- **Network cost transparency.** Growth turns count toward the
  same in-app token meter as user-initiated chats so cost is one
  number, not hidden background consumption.

---

## Implementation milestones

- **M8.0 (scaffolded today)** — `GrowthScheduler` + WorkManager wiring
  + `LearningJournal` stub that just writes a timestamped entry on
  each fire. Demonstrates the cadence works; no real learning yet.
- **M8.1** — Vosk on-device ASR, RawDataPurger, raw audio capture.
- **M8.2** — Gemma INT4 extractor → ObservationEvent → LearningVault
  (SQLCipher). Secret-mode triple-tap + password gate.
- **M8.3** — SelfOrganizer (clustering, reinforcement, promotion).
- **M8.4** — GoalGenerator + GrowthAgent (autonomous-run AgentLoop).
- **M8.5** — CapabilityTuner + tool-schema dynamic adjustment.
- **M8.6** — Vault Browser + Activity log UI in Secret panel.

M8.0 lands in this commit. The rest follows when M8 audio capture
is up.
