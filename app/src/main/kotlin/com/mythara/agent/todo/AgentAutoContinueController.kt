package com.mythara.agent.todo

import android.util.Log
import com.mythara.agent.AgentLoop
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The auto-wake loop that drains [AgentTodoStore].
 *
 * Pattern:
 *   1. Subscribe to [com.mythara.agent.AgentRunner.turnEvents].
 *   2. After each [AgentLoop.Turn.Finished], wait
 *      QUIET_INTERVAL_MS to give the user a chance to type a
 *      reply.
 *   3. If the user hasn't started a new turn AND there's a
 *      pending item in the todo store, auto-submit it as the
 *      next agent turn.
 *   4. Cap at MAX_AUTO_TURNS_PER_SESSION to prevent runaway
 *      cost / battery drain. The cap resets when the user
 *      submits a fresh turn manually.
 *
 * The submit happens via a callback the host wires from the
 * [com.mythara.agent.AgentRunner] (avoids a circular Hilt
 * dependency: AgentRunner injects this controller, and this
 * controller calls back into the runner via the callback set
 * during `start()`).
 *
 * This is the substrate for "agent maintains a todo and keeps
 * waking until done". The DECISION about WHAT goes into the
 * todo is the upstream extractor's job — currently triggered
 * only from chat-side hand-offs (the agent emits suggested
 * follow-ups in its final message); the daily-review extractor
 * + memory-pattern derivation is the next-round build.
 */
@Singleton
class AgentAutoContinueController @Inject constructor(
    private val todoStore: AgentTodoStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscription: Job? = null
    private var autoTurnsThisSession = 0

    /**
     * Callback wired by [com.mythara.agent.AgentRunner.bindAutoContinue]
     * to break a circular dependency. The controller doesn't
     * need to know how the agent runs — only how to ask it to
     * run with a given prompt.
     */
    @Volatile private var submitCallback: ((String) -> Unit)? = null

    fun bindSubmitCallback(submit: (String) -> Unit) {
        submitCallback = submit
    }

    /** Subscribe to the turn-events flow. Call once from
     *  AgentRunner.init or MytharaApp.onCreate. */
    fun start(turnEvents: SharedFlow<AgentLoop.Turn>) {
        if (subscription?.isActive == true) return
        subscription = scope.launch {
            turnEvents.collect { turn ->
                when (turn) {
                    is AgentLoop.Turn.Finished -> onTurnFinished()
                    else -> { /* delta / tool / error — let them flow */ }
                }
            }
        }
    }

    /** User submitted a fresh turn manually — reset the auto-turn
     *  counter so the cap restarts. AgentRunner.submit() should
     *  call this. */
    fun noteManualSubmit() {
        autoTurnsThisSession = 0
    }

    private suspend fun onTurnFinished() {
        // Brief quiet window so the user has time to type a reply
        // before we steal the floor with an auto-continuation.
        delay(QUIET_INTERVAL_MS)

        if (autoTurnsThisSession >= MAX_AUTO_TURNS_PER_SESSION) {
            Log.d(TAG, "auto-turn cap reached ($MAX_AUTO_TURNS_PER_SESSION) — pausing")
            return
        }

        val next = todoStore.pending().firstOrNull() ?: return
        val cb = submitCallback ?: run {
            Log.w(TAG, "submitCallback not bound — auto-continue disabled")
            return
        }

        Log.i(TAG, "auto-continue: picking up todo item id=${next.id} text=${next.text.take(40)}…")
        autoTurnsThisSession++
        // Mark the item Done OPTIMISTICALLY before submitting so
        // a re-tick can't double-fire it. If the agent fails the
        // item, the human user can re-add it; we'd rather skip
        // a successful task than loop on a failing one.
        todoStore.markStatus(next.id, AgentTodoStore.Status.Done)

        // The prompt frames the auto-continuation as the agent
        // picking up its own todo, with light context for the
        // model so it doesn't try to ask the user clarifying
        // questions about an item it generated itself.
        val prompt = buildString {
            append("[auto-continue] Picking up the next item from your internal todo list. ")
            append("Source: ${next.source.name.lowercase()}. Item: ${next.text}\n\n")
            append("Complete this end-to-end if possible — call the appropriate tools, ")
            append("don't ask the user clarifying questions unless absolutely necessary. ")
            append("If complete, briefly confirm what was done. If blocked, say what's needed.")
        }
        cb(prompt)
    }

    fun stop() {
        subscription?.cancel()
        subscription = null
    }

    companion object {
        private const val TAG = "Mythara/AutoContinue"

        /** How long to wait after a turn finishes before auto-
         *  picking the next item. Gives the user a chance to type
         *  a reply manually. 8 s is comfortable — the human
         *  reaction-then-typing window for "do you want me to
         *  also do X?" is typically 4-15 s. */
        const val QUIET_INTERVAL_MS = 8_000L

        /** Hard ceiling on auto-turns per user session. Prevents
         *  a misbehaving extractor from swarming the agent with
         *  cascading follow-ups. Reset on each manual submit. */
        const val MAX_AUTO_TURNS_PER_SESSION = 5
    }
}
