package com.mythara.wear

import android.content.Context

/**
 * Watch-side cache for the phone-pushed cluster snapshot — recent
 * tasks and favorite people + their latest insights. The phone's
 * WatchClusterDataPusher publishes a tiny delimited payload over the
 * Data Layer; [MytharaWearDataReceiver] drops the raw strings in
 * here, and the watch app's Tasks / People screens parse + render
 * the last cached snapshot.
 *
 * Delimiters MUST match WatchClusterDataPusher on the phone:
 *   RS (\u001E) between records · US (\u001F) between fields ·
 *   GS (\u001D) between list items.
 */
object ClusterDataStore {
    private const val PREFS = "mythara_cluster"
    private const val KEY_TASKS = "tasks"
    private const val KEY_PEOPLE = "people"
    private const val KEY_CALENDAR = "calendar"
    private const val KEY_REMINDER = "reminder"
    private const val KEY_AUDIT = "audit"

    private const val REC = "\u001E"
    private const val UNIT = "\u001F"
    private const val GROUP = "\u001D"

    data class WatchTask(val title: String, val status: String, val result: String)
    data class WatchPerson(val name: String, val insight: String, val points: List<String>)
    data class WatchCalEvent(val title: String, val startMs: Long, val location: String)
    data class WatchReminder(val title: String, val atMs: Long)
    data class WatchAuditEntry(
        val tsMs: Long,
        val kind: String,
        val title: String,
        val ok: Boolean,
        val detail: String,
    )

    fun saveTasks(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_TASKS, raw).apply()
    }

    fun savePeople(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_PEOPLE, raw).apply()
    }

    fun tasks(ctx: Context): List<WatchTask> =
        prefs(ctx).getString(KEY_TASKS, "").orEmpty().records().mapNotNull { row ->
            val f = row.split(UNIT)
            if (f.size >= 2) {
                WatchTask(
                    title = f[0],
                    status = f[1],
                    result = f.getOrElse(2) { "" },
                )
            } else {
                null
            }
        }

    fun people(ctx: Context): List<WatchPerson> =
        prefs(ctx).getString(KEY_PEOPLE, "").orEmpty().records().mapNotNull { row ->
            val f = row.split(UNIT)
            if (f.size >= 2) {
                WatchPerson(
                    name = f[0],
                    insight = f.getOrElse(1) { "" },
                    points = f.getOrElse(2) { "" }.split(GROUP).filter { it.isNotBlank() },
                )
            } else {
                null
            }
        }

    fun saveCalendar(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_CALENDAR, raw).apply()
    }

    fun calendar(ctx: Context): List<WatchCalEvent> =
        prefs(ctx).getString(KEY_CALENDAR, "").orEmpty().records().mapNotNull { row ->
            val f = row.split(UNIT)
            val start = f.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            WatchCalEvent(
                title = f.getOrElse(0) { "(untitled)" },
                startMs = start,
                location = f.getOrElse(2) { "" },
            )
        }

    fun saveReminder(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_REMINDER, raw).apply()
    }

    /** Raw cached reminder payload (still in the wire format). Used
     *  by the receiver to detect "is this a NEW reminder" before
     *  triggering the notification tone — periodic re-pushes of the
     *  same value should not chirp again. */
    fun reminderRaw(ctx: Context): String =
        prefs(ctx).getString(KEY_REMINDER, "").orEmpty()

    /** The single next upcoming reminder, or null when none is pending. */
    fun reminder(ctx: Context): WatchReminder? {
        val raw = prefs(ctx).getString(KEY_REMINDER, "").orEmpty()
        if (raw.isBlank()) return null
        val f = raw.split(UNIT)
        val at = f.getOrNull(1)?.toLongOrNull() ?: return null
        return WatchReminder(title = f.getOrElse(0) { "Reminder" }, atMs = at)
    }

    fun saveAudit(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_AUDIT, raw).apply()
    }

    /** The recent agent audit log, already ordered most-recent-first. */
    fun audit(ctx: Context): List<WatchAuditEntry> =
        prefs(ctx).getString(KEY_AUDIT, "").orEmpty().records().mapNotNull { row ->
            val f = row.split(UNIT)
            val ts = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            WatchAuditEntry(
                tsMs = ts,
                kind = f.getOrElse(1) { "" },
                title = f.getOrElse(2) { "" },
                ok = f.getOrElse(3) { "1" } == "1",
                detail = f.getOrElse(4) { "" },
            )
        }

    private fun String.records(): List<String> =
        if (isBlank()) emptyList() else split(REC).filter { it.isNotBlank() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
