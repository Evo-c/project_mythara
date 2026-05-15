package com.mythara.calendar

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic re-scanner for the calendar pre-announcer. Runs every
 * 15 minutes (WorkManager periodic floor). Each tick walks the next
 * hour of events and registers AlarmManager exact alarms for any
 * not yet alarmed.
 *
 * The alarms themselves fire INDEPENDENTLY of this worker — once
 * registered, they survive process death / app kill / reboot
 * (BootReceiver re-arms via reschedule on boot). This worker is just
 * the discoverer that picks up newly-added or recently-edited
 * events between alarm-fire times.
 */
@HiltWorker
class CalendarPreAnnounceWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val announcer: CalendarPreAnnouncer,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            announcer.scan()
            Result.success()
        }.getOrElse {
            Log.w(TAG, "scan failed: ${it.message}")
            Result.success() // never let the worker enter retry storm — next tick will retry naturally
        }
    }

    companion object {
        private const val TAG = "Mythara/PreAnnounceWk"
        private const val WORK_NAME = "calendar_preannounce_scan"

        /** Schedule the worker. Idempotent — KEEP policy means a second
         *  call is a no-op as long as the work is enqueued. Called
         *  from app startup. */
        fun ensureScheduled(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<CalendarPreAnnounceWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
