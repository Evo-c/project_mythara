package com.mythara.branding

/**
 * Process-wide sink for the most recent live heart-rate sample, read
 * by [WallpaperRenderer] each frame to pulse the rose's hexagon
 * nucleus and the active "neuron" overlay nodes in sync with the
 * user's actual heartbeat. Decoupled from the HR source so any
 * ingestion path (watch Data-Layer push, Health-Connect poller,
 * future direct-sensor reader) can publish the same way:
 *
 *   LiveWallpaperPulseSink.update(bpm)
 *
 * Reader returns null when the most recent push is older than
 * [STALE_AFTER_MS] — the renderer falls back to a static calm-breath
 * rate in that case (so a screen-locked phone with no recent HR
 * still looks like a living wallpaper, just not a personalised one).
 *
 * Volatile fields + no synchronisation: writes are atomic for Int /
 * Long on the JVM and the renderer is fine with reading a slightly-
 * out-of-sync (bpm, ts) pair — the worst case is one frame using
 * stale staleness, harmless.
 */
object LiveWallpaperPulseSink {
    @Volatile private var bpmValue: Int = 0
    @Volatile private var ts: Long = 0L

    /** Publish a fresh BPM reading. Clamps obvious garbage. */
    fun update(bpm: Int) {
        if (bpm !in 30..240) return
        bpmValue = bpm
        ts = System.currentTimeMillis()
    }

    /** Latest BPM if it arrived within [maxStaleMs] of now, else
     *  null. Renderer interprets null as "no live HR — use the
     *  default breath-rate fallback". */
    fun bpm(maxStaleMs: Long = STALE_AFTER_MS): Int? {
        if (ts == 0L) return null
        return if (System.currentTimeMillis() - ts <= maxStaleMs) bpmValue else null
    }

    /** Default staleness window — 3 min. Long enough to ride out the
     *  Fitbit / Samsung Health batch cadence (typically 1-2 min)
     *  without flapping the wallpaper between live + fallback states. */
    const val STALE_AFTER_MS = 3L * 60 * 1000
}
