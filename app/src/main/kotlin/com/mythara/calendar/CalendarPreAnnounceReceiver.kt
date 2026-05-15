package com.mythara.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wakes when an AlarmManager pre-announce alarm fires (3 minutes
 * before a calendar event). Pulls the title out of the intent and
 * hands off to [CalendarPreAnnouncer.announce], which picks TTS or
 * Music-Mode tones based on the toggle state.
 *
 * Uses goAsync() because the announcement path is suspending (TTS
 * speak / tone engine play) and would otherwise be cut off by the
 * 10-second BroadcastReceiver window.
 */
@AndroidEntryPoint
class CalendarPreAnnounceReceiver : BroadcastReceiver() {

    @Inject lateinit var announcer: CalendarPreAnnouncer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CalendarPreAnnouncer.ACTION_FIRE) return
        val title = intent.getStringExtra(CalendarPreAnnouncer.EXTRA_EVENT_TITLE).orEmpty()
        val eventId = intent.getLongExtra(CalendarPreAnnouncer.EXTRA_EVENT_ID, -1L)
        Log.d(TAG, "fire — eventId=$eventId, title='${title.take(40)}'")
        val pendingResult = goAsync()
        scope.launch {
            try {
                announcer.announce(title)
            } catch (e: Exception) {
                Log.w(TAG, "announce threw: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/PreAnnounceRcv"
    }
}
