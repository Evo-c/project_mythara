package com.mythara.wear

import android.util.Log
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the **next upcoming scheduled task** to the watch face's
 * insight complication. Replaces [WatchAgentMessageRelay]'s "last
 * agent chat message" with a forward-looking card so the wrist
 * always tells the user what's coming next today, dynamically as
 * time moves forward.
 *
 * Refresh triggers:
 *  - Task DB changes (new schedule, cancel, fire, recurrence re-arm).
 *    Observed via [TaskRepository.dao.observeRecent].
 *  - Wall-clock tick every [TICK_INTERVAL_MS] (~60 s) so the "in 5m"
 *    style countdown stays correct as time passes — and so a task
 *    whose `scheduledForMs` just crossed `now` rolls off and the
 *    next one slides up automatically.
 *
 * The watch complication shows whichever line is currently in
 * [WatchInsightPusher]; "no upcoming task" pushes an idle line so
 * the wrist doesn't keep showing yesterday's reminder.
 */
@Singleton
class WatchNextTaskRelay @Inject constructor(
    private val taskRepo: TaskRepository,
    private val pusher: WatchInsightPusher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tick = MutableStateFlow(0L)

    fun start() {
        // Wall-clock ticker — emit every minute so the "in N min"
        // countdown re-evaluates against the current time.
        scope.launch {
            while (true) {
                tick.value = System.currentTimeMillis()
                delay(TICK_INTERVAL_MS)
            }
        }
        // Combine task changes + ticker → recompute the headline line.
        scope.launch {
            taskRepo.dao.observeRecent(limit = 200)
                .combine(tick) { rows, _ -> rows }
                .map { rows -> formatLine(rows) }
                .distinctUntilChanged()
                .collect { line ->
                    runCatching { pusher.push(line) }
                        .onFailure { Log.w(TAG, "push failed: ${it.message}") }
                }
        }
    }

    private fun formatLine(rows: List<TaskEntity>): String {
        val now = System.currentTimeMillis()
        // PENDING tasks with a future schedule — the queue of "what's
        // coming up." Sorted by scheduledForMs ascending; we surface
        // the soonest.
        val upcoming = rows
            .filter { it.status == TaskStatus.PENDING.name }
            .filter { (it.scheduledForMs ?: 0L) > now }
            .sortedBy { it.scheduledForMs }
        val next = upcoming.firstOrNull() ?: return formatIdle(rows, now)
        val whenMs = next.scheduledForMs ?: return formatIdle(rows, now)
        val deltaMs = whenMs - now
        val whenStr = relativeTime(deltaMs, whenMs)
        // Title comes first so a glance is enough — the time hangs
        // off the end where the eye lands second.
        val title = next.title.trim().take(MAX_TITLE_CHARS)
        return "$title · $whenStr"
    }

    /** Fallback line when there are no upcoming tasks — show a count
     *  of what's already DONE today rather than "nothing planned"
     *  (less useful, more flat). */
    private fun formatIdle(rows: List<TaskEntity>, now: Long): String {
        val startOfDay = startOfDayMs(now)
        val firedToday = rows.count { row ->
            row.status == TaskStatus.DONE.name &&
                (row.completedMs ?: 0L) >= startOfDay
        }
        return when (firedToday) {
            0 -> "no scheduled tasks today"
            1 -> "1 task done today · nothing else queued"
            else -> "$firedToday tasks done today · nothing else queued"
        }
    }

    private fun relativeTime(deltaMs: Long, whenMs: Long): String {
        val mins = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        return when {
            mins < 1 -> "now"
            mins < 60 -> "in ${mins}m"
            mins < 24 * 60 -> {
                val sameDay = isSameDay(whenMs, System.currentTimeMillis())
                if (sameDay) HOUR_FMT.format(Date(whenMs)) else "tomorrow ${HOUR_FMT.format(Date(whenMs))}"
            }
            else -> DAY_FMT.format(Date(whenMs))
        }
    }

    private fun startOfDayMs(now: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun isSameDay(aMs: Long, bMs: Long): Boolean {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = aMs }
        val b = java.util.Calendar.getInstance().apply { timeInMillis = bMs }
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }

    companion object {
        private const val TAG = "Mythara/NextTaskRelay"

        /** Wall-clock tick cadence. 60 s is the right resolution for
         *  the "in N min" countdown — finer than a minute would burn
         *  battery without buying the user anything since the watch
         *  complication itself only refreshes when we push. */
        private const val TICK_INTERVAL_MS = 60_000L

        /** Cap on the task title we copy into the push. The Insight
         *  pusher already trims to 120 chars; this leaves headroom
         *  for the " · in 12m" suffix. */
        private const val MAX_TITLE_CHARS = 80

        private val HOUR_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DAY_FMT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    }
}
