package com.mythara.agent.queue

import android.content.Context
import android.util.Log
import com.mythara.agent.AgentLoop
import com.mythara.agent.AgentRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Persistent, serialized auto-reply queue. Every notification that the
 * dispatcher decides should trigger an agent turn lands here FIRST,
 * then a single drain coroutine runs them one at a time against
 * [AgentRunner]. The previous fire-and-forget path lost messages to:
 *
 *  - the 16-slot DROP_OLDEST SharedFlow buffer overflowing under burst
 *  - network failures mid-turn with no retry
 *  - process death between "notification arrived" and "turn complete"
 *  - parallel agent loops racing on shared history writes
 *
 * Single-drain serialization is intentional. Auto-reply turns hold the
 * mic, the TTS, and the streaming MiniMax SSE — running two at once
 * produces garbled audio (overlapping TTS) and burns 2x tokens for no
 * extra signal. The dispatcher used to gate on [AgentRunner.busy] for
 * the same reason but that gate silently DROPPED notifications; the
 * queue defers them instead.
 *
 * Crash recovery: every row's status goes PENDING → IN_FLIGHT →
 * HANDLED / FAILED / SKIPPED. On process boot, [start] calls
 * [PendingReplyDao.requeueStuck] to reset any IN_FLIGHT rows older
 * than the stuck threshold (process died mid-turn) back to PENDING so
 * the new process picks them up.
 *
 * The drain wakes on three signals:
 *  1. New enqueue → immediate
 *  2. Periodic [PendingReplyKickWorker] every 15 min → catches anything
 *     a process-restart didn't recover (e.g. user force-quit the app
 *     before the FGS-cancelled coroutine could re-fire)
 *  3. Backoff timer → after a retryable failure
 */
@Singleton
class PendingReplyQueue @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: PendingReplyRepository,
    private val runner: AgentRunner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Wake channel — every enqueue or external "kick" sends a tick.
     * Buffer = 1 with DROP_OLDEST because we only need to know there's
     * SOMETHING to drain; a flood of enqueues collapses to one wake.
     */
    private val wake = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var drainJob: Job? = null
    @Volatile private var started = false

    /**
     * Called once from [com.mythara.MytharaApp.onCreate]. Idempotent.
     * Runs the cold-start recovery sweep BEFORE the drain so the very
     * first poll picks up any orphaned IN_FLIGHT rows.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                val recovered = repo.dao.requeueStuck(
                    cutoffMs = System.currentTimeMillis() - STUCK_INFLIGHT_MS,
                )
                if (recovered > 0) {
                    Log.w(TAG, "recovered $recovered stuck IN_FLIGHT row(s) from prior process")
                }
                val gc = repo.dao.gcOldTerminal(
                    cutoffMs = System.currentTimeMillis() - GC_RETENTION_MS,
                )
                if (gc > 0) Log.d(TAG, "gc'd $gc old terminal row(s)")
            }
            kick() // pick up anything left in PENDING
        }
        drainJob = scope.launch { drainLoop() }
        Log.d(TAG, "PendingReplyQueue started")
    }

    /**
     * Enqueue a turn. Idempotent on [dedupKey] — re-firing the same
     * notification (Android fires onNotificationPosted multiple times
     * per message for typing/read updates) doesn't generate a second
     * turn. Returns true if a NEW row was inserted, false if dup.
     */
    suspend fun enqueue(
        pkg: String,
        senderTitle: String,
        body: String,
        route: PendingReplyRoute,
        turnText: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        val key = makeDedupKey(pkg, senderTitle, body, now)
        val row = PendingReplyEntity(
            tsMillis = now,
            pkg = pkg,
            senderTitle = senderTitle,
            body = body,
            route = route.name,
            turnText = turnText,
            status = PendingReplyStatus.PENDING.name,
            attempts = 0,
            lastError = null,
            dedupKey = key,
            nextAttemptMs = now,
            inFlightSinceMs = null,
        )
        val id = runCatching { repo.dao.insertIfAbsent(row) }.getOrDefault(-1L)
        val wasNew = id > 0L
        if (wasNew) {
            Log.d(TAG, "enqueued #$id route=${route.name} pkg=$pkg sender=$senderTitle")
            kick()
        } else {
            Log.d(TAG, "dedup hit — skipping repeat from $pkg/$senderTitle")
        }
        return wasNew
    }

    /** Tickle the drain — safe to call from anywhere, no-op if already busy. */
    fun kick() {
        wake.tryEmit(Unit)
    }

    private suspend fun drainLoop() {
        // Initial poll on startup (no enqueue has happened yet).
        drainOnce()
        wake.collect {
            drainOnce()
        }
    }

    private suspend fun drainOnce() {
        while (true) {
            val now = System.currentTimeMillis()
            val ready = runCatching { repo.dao.listReady(nowMs = now, limit = 1) }
                .getOrDefault(emptyList())
            val row = ready.firstOrNull() ?: return
            val claimed = runCatching {
                repo.dao.markInFlight(id = row.id, nowMs = now)
            }.getOrDefault(0)
            if (claimed == 0) {
                // Another drain (shouldn't happen — we're single-threaded)
                // already grabbed it. Loop and look for the next one.
                continue
            }
            handleOne(row.copy(attempts = row.attempts + 1, status = PendingReplyStatus.IN_FLIGHT.name))
        }
    }

    private suspend fun handleOne(row: PendingReplyEntity) {
        Log.d(TAG, "drain → running #${row.id} (attempt ${row.attempts}/${MAX_ATTEMPTS}) ${row.pkg}/${row.senderTitle}")
        val outcome = runAgentForRow(row)
        when (outcome) {
            is TurnOutcome.Handled -> {
                repo.dao.markHandled(row.id)
                Log.d(TAG, "row #${row.id} → HANDLED")
            }
            is TurnOutcome.Skipped -> {
                repo.dao.markSkipped(row.id, outcome.reason)
                Log.d(TAG, "row #${row.id} → SKIPPED (${outcome.reason})")
            }
            is TurnOutcome.RetryableError -> {
                if (row.attempts >= MAX_ATTEMPTS) {
                    repo.dao.markFailedTerminal(row.id, "${outcome.message} (out of retries)")
                    Log.w(TAG, "row #${row.id} → FAILED (out of retries): ${outcome.message}")
                } else {
                    val backoffMs = backoffForAttempt(row.attempts)
                    val nextMs = System.currentTimeMillis() + backoffMs
                    repo.dao.markForRetry(row.id, outcome.message, nextMs)
                    Log.w(TAG, "row #${row.id} → retry in ${backoffMs / 1000}s: ${outcome.message}")
                    // Schedule a wake-up so the drain re-polls after the
                    // backoff window — the kick coalesces with any other
                    // enqueue that lands meanwhile.
                    scope.launch {
                        delay(backoffMs + 250L)
                        kick()
                    }
                }
            }
            is TurnOutcome.HardError -> {
                repo.dao.markFailedTerminal(row.id, outcome.message)
                Log.w(TAG, "row #${row.id} → FAILED (non-retryable): ${outcome.message}")
            }
            TurnOutcome.Timeout -> {
                // Treat like a retryable network blip.
                if (row.attempts >= MAX_ATTEMPTS) {
                    repo.dao.markFailedTerminal(row.id, "timed out (out of retries)")
                    Log.w(TAG, "row #${row.id} → FAILED on timeout")
                } else {
                    val backoffMs = backoffForAttempt(row.attempts)
                    repo.dao.markForRetry(
                        row.id, "timed out", System.currentTimeMillis() + backoffMs,
                    )
                    scope.launch {
                        delay(backoffMs + 250L)
                        kick()
                    }
                }
            }
        }
    }

    /**
     * Fire the turn through [AgentRunner] and wait for the terminal
     * Turn event (Finished or Error) for THIS submission. Identifying
     * which Turn belongs to our submission is the awkward bit — the
     * runner's [AgentRunner.turnEvents] is a process-wide flow that
     * carries every concurrent loop's events. We rely on the queue's
     * SERIALIZATION guarantee (single-drain) to know that the next
     * Finished / Error event after we submit IS ours; another caller
     * (a user-typed message in the chat surface) could in theory
     * race, in which case we'd wait for the second terminal event.
     * The bounded TURN_TIMEOUT_MS prevents that from hanging forever.
     */
    private suspend fun runAgentForRow(row: PendingReplyEntity): TurnOutcome {
        val done = CompletableDeferred<TurnOutcome>()
        val watcher = scope.launch {
            runner.turnEvents.collect { turn ->
                when (turn) {
                    is AgentLoop.Turn.Finished -> {
                        if (done.isActive) done.complete(classifyFinished(turn))
                    }
                    is AgentLoop.Turn.Error -> {
                        if (done.isActive) {
                            done.complete(
                                if (turn.retryable) TurnOutcome.RetryableError(turn.message)
                                else TurnOutcome.HardError(turn.message),
                            )
                        }
                    }
                    else -> { /* Delta / ToolStart / ToolEnd — ignore */ }
                }
            }
        }
        runner.submit(text = row.turnText, fromVoice = false)
        val outcome = withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() } ?: TurnOutcome.Timeout
        watcher.cancel()
        return outcome
    }

    /**
     * A Finished turn whose stripped final text is [AgentLoop.NOSURFACE_TOKEN]
     * means the agent intentionally chose NOT to reply (triage said
     * "marketing / OTP / drop"). Mark as SKIPPED, not HANDLED — the
     * distinction matters for the UI surface ("did anything actually
     * go out?") and for analytics.
     */
    private fun classifyFinished(turn: AgentLoop.Turn.Finished): TurnOutcome {
        val cleaned = turn.finalText.trim()
        return if (cleaned.equals(AgentLoop.NOSURFACE_TOKEN, ignoreCase = true)) {
            TurnOutcome.Skipped("agent declined to reply (NOSURFACE)")
        } else {
            TurnOutcome.Handled
        }
    }

    private fun backoffForAttempt(attempt: Int): Long {
        // Exponential with cap: 30s, 2m, 8m, 30m, 30m, …
        val base = (30_000L * 2.0.pow((attempt - 1).coerceAtLeast(0))).toLong()
        return min(base, MAX_BACKOFF_MS)
    }

    /**
     * SHA-256 of (pkg | sender | body | minute-bucket). The minute
     * bucket means re-firing the SAME body 90s later (e.g. user kept
     * receiving "Are you there?" pings) is treated as a NEW message,
     * which is what we want — they need a fresh reply.
     */
    private fun makeDedupKey(pkg: String, sender: String, body: String, nowMs: Long): String {
        val bucket = nowMs / 60_000L
        val raw = "$pkg|$sender|$body|$bucket".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private sealed interface TurnOutcome {
        data object Handled : TurnOutcome
        data class Skipped(val reason: String) : TurnOutcome
        data class RetryableError(val message: String) : TurnOutcome
        data class HardError(val message: String) : TurnOutcome
        data object Timeout : TurnOutcome
    }

    companion object {
        private const val TAG = "Mythara/ReplyQueue"

        /** A turn that takes longer than this is assumed wedged; we count it as a retryable failure. */
        private const val TURN_TIMEOUT_MS = 90_000L

        /** IN_FLIGHT rows older than this on cold start are reclaimed to PENDING. */
        private const val STUCK_INFLIGHT_MS = 5L * 60_000L

        /** Max retries before a row is parked in FAILED forever. */
        private const val MAX_ATTEMPTS = 4

        /** Backoff cap so retries don't drift past half an hour. */
        private const val MAX_BACKOFF_MS = 30L * 60_000L

        /** Terminal rows older than this are GC'd on startup. */
        private const val GC_RETENTION_MS = 14L * 24L * 60L * 60L * 1000L
    }
}
