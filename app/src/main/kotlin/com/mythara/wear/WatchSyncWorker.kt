package com.mythara.wear

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
 * Periodic 15-minute push of every watch surface — keeps the wrist's
 * insight line, complications, and cluster lists fresh even when the
 * user hasn't opened the app and the in-process loops are sleeping.
 *
 * 15 min is the WorkManager periodic floor. Backstops the in-process
 * relays (which sleep after their own intervals) and the per-DB-change
 * triggers (which only fire when something actually changes).
 *
 * Idempotent — the per-surface pushers all already dedupe / overwrite
 * cleanly, so running an extra sync costs only a few KB of Wearable
 * Data Layer traffic.
 */
@HiltWorker
class WatchSyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val sync: WatchSyncManager,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            sync.syncNow()
            Result.success()
        }.getOrElse {
            Log.w(TAG, "sync worker failed: ${it.message}")
            Result.success() // never enter retry storms — next tick will try again
        }
    }

    companion object {
        private const val TAG = "Mythara/WatchSyncWk"
        private const val WORK_NAME = "watch_sync_periodic"

        fun ensureScheduled(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<WatchSyncWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
