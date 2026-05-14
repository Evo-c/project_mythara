package com.mythara.tasks

import android.util.Log
import com.mythara.agent.AgentRunner
import com.mythara.agent.Thinks
import com.mythara.memory.DeviceIdStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heartbeat task pickup + execution.
 *
 * On every [HeartbeatSyncer] tick (~5 min when the process is alive,
 * 15 min from the WorkManager fallback otherwise):
 *
 *   1. List tasks claimable by THIS device — status=PENDING, target
 *      either null ("any device") or this device's id, and the
 *      schedule has elapsed.
 *   2. Try to atomically claim the task ([TaskDao.tryClaim] —
 *      conditional UPDATE that succeeds only if status was still
 *      PENDING). Losing the race to another device on the same null-
 *      target task is fine and expected; we just move on.
 *   3. Execute. Phase 1 implementation is the simplest possible
 *      interpretation: submit the task body as a turn to the agent
 *      via [AgentRunner.submit], same path a typed message would
 *      take. The agent's tool call surface picks up whatever the task
 *      describes ("send mom an SMS reminder about dinner", "check the
 *      battery on dev:xxxx", etc.). Phase 2 can add dedicated
 *      "task kinds" with structured handlers.
 *   4. Mark DONE / FAILED with the agent's final text as the result.
 *
 * Status updates are persisted locally and ship out on the very next
 * sync (the same heartbeat tick fires sync RIGHT BEFORE this), so
 * the requesting device sees the result within one round-trip.
 *
 * Handoff safety: this executor NEVER routes tasks across devices on
 * its own. The user (or the agent acting on the user's request) sets
 * targetDeviceId explicitly via the create_task / handoff_task tool;
 * we just pick up what was explicitly addressed to us OR what's
 * marked "any device". The 5-min cadence + cooperative claiming means
 * the same task isn't run twice unless WorkManager and the in-process
 * timer both fire in the same window — and even then the atomic
 * UPDATE makes the second claim a no-op.
 */
@Singleton
class TaskExecutor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: TaskRepository,
    private val runner: AgentRunner,
    private val deviceIdStore: DeviceIdStore,
) {

    suspend fun tick(maxTasks: Int = 3): Int {
        val myId = runCatching { deviceIdStore.id() }.getOrElse { return 0 }
        val now = System.currentTimeMillis()
        val claimable = runCatching {
            repo.dao.listClaimable(deviceId = myId, nowMs = now, limit = maxTasks)
        }.getOrDefault(emptyList())
        if (claimable.isEmpty()) {
            Log.v(TAG, "tick: no claimable tasks for $myId")
            return 0
        }
        var done = 0
        for (task in claimable) {
            val claimed = runCatching { repo.dao.tryClaim(task.id, myId, now) }.getOrDefault(0)
            if (claimed == 0) {
                // Lost the race to another device — fine, move on.
                continue
            }
            Log.d(TAG, "tick: claimed task ${task.id} '${task.title.take(40)}'")
            runOne(task.copy(claimedByDeviceId = myId, claimedMs = now), myId)
            done++
        }
        return done
    }

    private suspend fun runOne(task: TaskEntity, myId: String) {
        // Mark RUNNING so other devices viewing the task see the
        // state transition on their next sync (and don't think it's
        // stalled).
        runCatching { repo.dao.markRunning(task.id) }

        // Phase 2: submit the task body to the agent and BLOCK on the
        // turn so the agent's actual final answer lands in result_text
        // — that's what the Tasks screen renders and what ships back to
        // the requesting device on the next sync. `submitAndAwait`
        // keeps the full AgentRunner lifecycle (FGS keepalive, TTS,
        // reply notification); we just also get the text back.
        //
        // withTimeoutOrNull bounds a single task so a wedged turn
        // can't stall the heartbeat indefinitely — maxTasks in tick()
        // caps the per-tick total. The agent turn itself runs in
        // AgentRunner's own scope, so a timeout here only abandons our
        // WAIT; the turn finishes on its own and still posts its reply.
        runCatching {
            val prompt = buildPrompt(task, myId)
            val finalText = withTimeoutOrNull(RESULT_TIMEOUT_MS) {
                runner.submitAndAwait(text = prompt, fromVoice = false)
            }
            if (finalText == null) {
                repo.dao.markTerminal(
                    task.id,
                    TaskStatus.FAILED.name,
                    "agent produced no answer (timed out or errored)",
                    System.currentTimeMillis(),
                )
            } else {
                repo.dao.markTerminal(
                    task.id,
                    TaskStatus.DONE.name,
                    summariseResult(finalText),
                    System.currentTimeMillis(),
                )
            }
        }.onFailure { e ->
            Log.w(TAG, "task ${task.id} threw: ${e.message}")
            repo.dao.markTerminal(
                task.id,
                TaskStatus.FAILED.name,
                e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis(),
            )
        }
    }

    /**
     * Tidy the agent's raw final text for the task card + the synced
     * JSONL row: drop `<think>` reasoning and the max-iteration
     * sentinel, trim, and cap the length so a rambling answer doesn't
     * bloat either the UI row or the cross-device payload.
     */
    private fun summariseResult(raw: String): String {
        val cleaned = Thinks.strip(raw)
            .removeSuffix(" [hit max iterations]")
            .trim()
        if (cleaned.isBlank()) return "(agent produced no text)"
        return if (cleaned.length > RESULT_MAX_CHARS) {
            cleaned.take(RESULT_MAX_CHARS).trimEnd() + "…"
        } else {
            cleaned
        }
    }

    /**
     * Build the turn text submitted to the agent for a claimed task.
     *
     * The phrasing is deliberately assertive: an earlier, more neutral
     * format ("target=<id>") made the model reason "this targets the
     * Pixel Fold — not me" and try to re-route the task instead of
     * doing it, because it didn't connect "I am running ON device
     * <id>". So we state it plainly: THIS device is the one that must
     * act, do it now, reply with the result — don't hand it off.
     */
    private fun buildPrompt(task: TaskEntity, myId: String): String = buildString {
        append("[handoff-task] This task has been routed to THIS device and ")
        append("YOU are the device that must carry it out — you are running on ")
        append("device `").append(myId).append("`. ")
        append("Do it NOW using your tools and reply with the actual result. ")
        append("Do NOT create another task, hand it off, or say it'll be done later — ")
        append("there is no other device to defer to, you ARE the target.\n")
        append("requester=").append(task.requesterDeviceId.takeLast(8))
        append(" id=").append(task.id.take(8)).append('\n')
        append("title: ").append(task.title).append('\n')
        if (task.body.isNotBlank()) append(task.body)
    }

    companion object {
        private const val TAG = "Mythara/TaskExec"

        /** Per-task wait cap. A single agent turn finishing in over two
         *  minutes is pathological; past this we abandon the wait, mark
         *  the task FAILED, and move on so the heartbeat isn't wedged. */
        private const val RESULT_TIMEOUT_MS = 2L * 60 * 1000

        /** Max chars of agent text kept in `result_text` — keeps the
         *  Tasks card readable and the synced task ledger small. */
        private const val RESULT_MAX_CHARS = 800
    }
}
