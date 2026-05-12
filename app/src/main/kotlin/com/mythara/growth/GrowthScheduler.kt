package com.mythara.growth

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers Mythara's self-learning cadences with WorkManager.
 *
 * Two periodic jobs + one fire-now hook:
 *  - **Nightly** every 24h, requires the phone to be charging + battery
 *    not low. Real-world Pixels fire this around 2–4am when plugged in
 *    overnight.
 *  - **Weekly** every 7 days, requires battery not low (no charging
 *    constraint — Sunday review can fire while running on battery).
 *  - **fireNow()** triggers a one-shot job for testing or impatient
 *    users via the Secret panel.
 *
 * Both periodic jobs use `ExistingPeriodicWorkPolicy.UPDATE` so registering
 * a different cadence (e.g., user changes from nightly to every-12h)
 * replaces the existing schedule without dropping the queue.
 *
 * M8.0 stub: scheduling works, the worker body just journals. M8.1+ wires
 * the real pipeline behind [GrowthWorker.doWork].
 */
@Singleton
class GrowthScheduler @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        registerNightly()
        registerWeekly()
    }

    fun pause() {
        wm.cancelUniqueWork(GrowthWorker.UNIQUE_NIGHTLY)
        wm.cancelUniqueWork(GrowthWorker.UNIQUE_WEEKLY)
    }

    fun fireNow(kind: String = "manual") {
        val req = OneTimeWorkRequestBuilder<GrowthWorker>()
            .setInputData(Data.Builder().putString(GrowthWorker.KEY_KIND, kind).build())
            .build()
        wm.enqueueUniqueWork(
            "${GrowthWorker.UNIQUE_NIGHTLY}_oneshot",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            req,
        )
    }

    private fun registerNightly() {
        val req = PeriodicWorkRequestBuilder<GrowthWorker>(Duration.ofHours(24))
            .setInputData(Data.Builder().putString(GrowthWorker.KEY_KIND, "nightly").build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(15))  // don't fire right at install
            .build()
        wm.enqueueUniquePeriodicWork(
            GrowthWorker.UNIQUE_NIGHTLY,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    private fun registerWeekly() {
        val req = PeriodicWorkRequestBuilder<GrowthWorker>(Duration.ofDays(7))
            .setInputData(Data.Builder().putString(GrowthWorker.KEY_KIND, "weekly").build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(Duration.ofHours(2))
            .build()
        wm.enqueueUniquePeriodicWork(
            GrowthWorker.UNIQUE_WEEKLY,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}
