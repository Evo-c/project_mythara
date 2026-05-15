package com.mythara.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.data.CalendarPreAnnounceStore
import com.mythara.data.MusicModeStore
import com.mythara.mic.Tts
import com.mythara.music.MusicReplyEncoder
import com.mythara.music.MusicToneEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the user's calendars for upcoming events and registers an
 * AlarmManager exact-time alarm 3 minutes before each one. When the
 * alarm fires, [announce] is invoked: spoken via TTS, OR played as a
 * tone phrase if Music Mode is on.
 *
 * Pipeline:
 *   1. [scan] (called periodically by [CalendarPreAnnounceWorker])
 *      walks the next [SCAN_WINDOW_MS] of CalendarContract events.
 *   2. For each event whose start is more than [LEAD_MS] ahead AND
 *      hasn't already been alarmed (per [CalendarPreAnnounceStore]),
 *      register an alarm at `start - LEAD_MS` and persist the
 *      event-instance key.
 *   3. The system fires [CalendarPreAnnounceReceiver] at that time;
 *      the receiver calls [announce] with the event title.
 *   4. [announce] picks TTS or tones based on the Music Mode toggle
 *      and speaks "<title> in 3 minutes — get ready" (or the
 *      Music-Mode equivalent).
 *
 * Off when [CalendarPreAnnounceStore.enabledFlow] is false.
 */
@Singleton
class CalendarPreAnnouncer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: CalendarPreAnnounceStore,
    private val musicMode: MusicModeStore,
    private val tts: dagger.Lazy<Tts>,
    private val toneEngine: dagger.Lazy<MusicToneEngine>,
    private val encoder: dagger.Lazy<MusicReplyEncoder>,
) {

    /** Walk upcoming events and register alarms for unalarmed ones.
     *  Idempotent — re-running is safe and cheap; alarms for already-
     *  scheduled event-instances are skipped via the alarmed-keys
     *  set in [CalendarPreAnnounceStore]. */
    suspend fun scan() {
        if (!store.isEnabled()) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "scan skipped — READ_CALENDAR not granted")
            return
        }
        val now = System.currentTimeMillis()
        store.pruneStaleAlarmedKeys(now)
        val events = queryEvents(now, now + SCAN_WINDOW_MS, max = 50)
        if (events.isEmpty()) {
            Log.v(TAG, "scan: no events in next ${SCAN_WINDOW_MS / 60_000} min")
            return
        }
        var registered = 0
        for (e in events) {
            // Skip events that are too close — alarm would fire late
            // or never. The pre-announce only makes sense ≥ LEAD_MS
            // ahead.
            val fireAt = e.startMs - LEAD_MS
            if (fireAt <= now + ALARM_SAFETY_MS) continue
            val key = "${e.id}:${e.startMs}"
            if (store.isAlarmed(key)) continue
            val title = (e.title ?: "untitled event").take(80)
            registerAlarm(fireAt, e.id, e.startMs, title)
            store.markAlarmed(key)
            registered++
        }
        if (registered > 0) {
            Log.d(TAG, "scan registered $registered new pre-announce alarm(s)")
        }
    }

    /** Wake from [CalendarPreAnnounceReceiver] — actually do the
     *  announcement. Routes to TTS or tones based on Music Mode. */
    suspend fun announce(title: String) {
        if (!store.isEnabled()) return
        val text = buildString {
            append(title.ifBlank { "Untitled event" })
            append(" in 3 minutes — get ready.")
        }
        val musicOn = runCatching { musicMode.enabledFlow().first() }
            .getOrDefault(false)
        if (musicOn) {
            // Use the same MusicReplyEncoder + MusicToneEngine path
            // chat replies use, so the announcement lands in the
            // user's secret-language vocabulary.
            runCatching {
                val motifs = encoder.get().encode(text).map { it.motif }.filter { it.notes.isNotEmpty() }
                if (motifs.isNotEmpty()) {
                    toneEngine.get().play(motifs, sourceKey = text)
                    Log.d(TAG, "announced (music) for '$title'")
                }
            }.onFailure { Log.w(TAG, "music announce failed: ${it.message}") }
        } else {
            runCatching {
                tts.get().speak(text)
                Log.d(TAG, "announced (tts) for '$title'")
            }.onFailure { Log.w(TAG, "tts announce failed: ${it.message}") }
        }
    }

    private fun registerAlarm(fireAtMs: Long, eventId: Long, startMs: Long, title: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, CalendarPreAnnounceReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_EVENT_START_MS, startMs)
            putExtra(EXTRA_EVENT_TITLE, title)
        }
        // Distinct request code per event-instance so each alarm
        // overwrites itself cleanly on re-scan rather than colliding
        // with siblings. Hash the (id, start) tuple — collisions
        // across 2^31 possible request codes are vanishingly rare
        // in practice.
        val requestCode = (eventId * 31 + startMs).hashCode()
        val pi = PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm register failed: ${e.message}")
        }
    }

    private data class CalEvent(val id: Long, val title: String?, val startMs: Long)

    private fun queryEvents(startMs: Long, endMs: Long, max: Int): List<CalEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(
                uri, projection, null, null,
                "${CalendarContract.Instances.BEGIN} ASC LIMIT $max",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (c.moveToNext()) {
                    if (c.getInt(allDayIdx) == 1) continue        // skip all-day rows
                    out.add(
                        CalEvent(
                            id = c.getLong(idIdx),
                            title = c.getString(titleIdx),
                            startMs = c.getLong(beginIdx),
                        ),
                    )
                }
            }
        }
        return out
    }

    companion object {
        private const val TAG = "Mythara/PreAnnounce"

        /** How far ahead of an event start we fire the announcement. */
        const val LEAD_MS = 3L * 60 * 1000

        /** How far ahead the periodic scanner looks for events to
         *  pre-register alarms. Should comfortably exceed the worker
         *  cadence (15 min) + LEAD_MS so nothing is missed even if a
         *  scan happens just before an event lands. */
        private const val SCAN_WINDOW_MS = 60L * 60 * 1000

        /** Minimum time between "now" and the alarm fire-time we'll
         *  bother registering. AlarmManager will fire late if the
         *  delta is smaller than the OS-imposed Doze tolerance. */
        private const val ALARM_SAFETY_MS = 30_000L

        // Receiver intent action + extras.
        const val ACTION_FIRE = "com.mythara.calendar.PRE_ANNOUNCE_FIRE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_START_MS = "event_start_ms"
        const val EXTRA_EVENT_TITLE = "event_title"
    }
}
