package com.mythara.memory

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager job that pushes Mythara's learnings to GitHub. Triggered by
 * three paths:
 *  - Periodic (24h, RequiresCharging + UNMETERED network), via
 *    [MemorySyncScheduler.start]. The constraints mean the job only
 *    fires overnight while plugged into Wi-Fi — never burns mobile data
 *    or warm cycles.
 *  - One-shot from the Settings panel's "Sync now" button, via
 *    [MemorySyncScheduler.fireNow]. Constraints are relaxed so the user
 *    can sync immediately.
 *  - Indirectly, after the nightly GrowthWorker run — see
 *    [com.mythara.growth.GrowthScheduler].
 */
@HiltWorker
class MemorySyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val sync: MemorySync,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val report = runCatching { sync.runSync(forcePush = inputData.getBoolean(KEY_FORCE, false)) }
            .getOrElse {
                Log.w(TAG, "memory sync threw ${it.message}")
                MemorySync.Report(ok = false, message = it.message ?: "unknown error")
            }
        Log.d(TAG, "memory sync: ok=${report.ok} msg=${report.message} wrote=${report.filesWritten}")
        return if (report.ok) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "Mythara/Memory"
        const val KEY_FORCE = "force"
        const val UNIQUE_PERIODIC = "mythara_memory_sync_periodic"
        const val UNIQUE_ONESHOT = "mythara_memory_sync_oneshot"
    }
}

@Singleton
class MemorySyncScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val memorySettings: MemorySettings,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<MemorySyncWorker>(Duration.ofHours(24))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(30))
            .build()
        wm.enqueueUniquePeriodicWork(
            MemorySyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    fun pause() {
        wm.cancelUniqueWork(MemorySyncWorker.UNIQUE_PERIODIC)
        wm.cancelUniqueWork(MemorySyncWorker.UNIQUE_ONESHOT)
    }

    fun fireNow(force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<MemorySyncWorker>()
            .setInputData(Data.Builder().putBoolean(MemorySyncWorker.KEY_FORCE, force).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        wm.enqueueUniqueWork(
            MemorySyncWorker.UNIQUE_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    /**
     * Fire a one-shot sync only if the last successful sync is older
     * than [maxStaleMs]. No-op when sync isn't configured (no PAT) or
     * isn't enabled. Used by the notification-arrival path so a real
     * push notification gets backed up to GitHub within ~an hour even
     * if the 24h periodic sync hasn't ticked yet.
     *
     * Idempotent — the UNIQUE_ONESHOT policy collapses bursts of calls
     * into a single enqueued worker.
     */
    suspend fun fireNowIfStale(maxStaleMs: Long = 60L * 60 * 1000) {
        val snap = memorySettings.snapshot()
        if (!snap.enabled || !snap.configured) return
        val lastSync = snap.lastSyncTs
        val now = System.currentTimeMillis()
        if (lastSync > 0 && (now - lastSync) < maxStaleMs) return
        fireNow(force = false)
    }
}
