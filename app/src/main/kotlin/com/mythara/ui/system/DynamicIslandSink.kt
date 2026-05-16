package com.mythara.ui.system

import androidx.compose.ui.graphics.Color
import com.mythara.ui.theme.MytharaColors
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Process-wide sink for the iPhone-Dynamic-Island-style pill in
 * the centre of the [MytharaStatusBar]. Anything with a momentary
 * piece of "this is what Mythara is doing right now" can push an
 * [Insight] here; the pill morphs to display it for [Insight.ttlMs]
 * milliseconds, then reverts to the idle rose+wordmark.
 *
 * Designed for high-frequency low-importance updates: agent
 * thinking spinners, fresh insight arrivals, "next reminder in
 * 14m" countdowns, HR alerts, auto-triage notices. Anything that
 * the user benefits from seeing for a few seconds without
 * committing to a full notification.
 *
 * Multiple insights queue and rotate — when one expires, the next
 * pending one (if any) takes over, otherwise we fall back to idle.
 *
 * Thread-safe via ConcurrentLinkedQueue. The sink is read by the
 * [DynamicIsland] composable's poll loop (every 500 ms) so we don't
 * need a Flow — keeps the sink trivially testable from a synchronous
 * caller.
 */
object DynamicIslandSink {

    /** A single momentary piece of state for the pill to surface.
     *  `text` is what's displayed (kept short — pill is narrow).
     *  `accent` colours the text + the optional left-side dot.
     *  `ttlMs` is how long this insight remains active before
     *  expiring; the pill auto-rotates to the next pending or
     *  reverts to idle. */
    data class Insight(
        val text: String,
        val accent: Color = MytharaColors.Charple,
        val ttlMs: Long = 5_000L,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - createdAtMs > ttlMs
    }

    private val pending = ConcurrentLinkedQueue<Insight>()
    @Volatile private var active: Insight? = null

    /** Push an insight to the pill. If the pill is idle, this
     *  insight becomes active immediately. Otherwise it queues
     *  behind the current one. */
    fun push(insight: Insight) {
        pending.add(insight)
    }

    /** Convenience for the most-common shape. */
    fun push(text: String, accent: Color = MytharaColors.Charple, ttlMs: Long = 5_000L) {
        push(Insight(text = text, accent = accent, ttlMs = ttlMs))
    }

    /** Return the currently-active insight (if any) — null when
     *  the pill should render its idle face. Called by the
     *  [DynamicIsland] composable on each render tick; this method
     *  is also responsible for advancing the queue when the active
     *  insight expires. */
    fun current(): Insight? {
        // Expire the current active if past its TTL, then promote
        // the next pending one. Loop in case multiple have piled up
        // while we were idle (e.g. the user backgrounded the app).
        var cur = active
        if (cur != null && cur.isExpired()) {
            cur = null
            active = null
        }
        if (cur == null) {
            while (true) {
                val next = pending.poll() ?: break
                if (!next.isExpired()) {
                    active = next
                    return next
                }
                // else discard the stale entry and keep polling
            }
        }
        return active
    }

    /** Clear everything — used by the renderer when the user
     *  taps the pill (interpreted as "I've seen it"). Resets to
     *  idle face. */
    fun clear() {
        active = null
        pending.clear()
    }
}
